/**
 * Copyright (c) 2014, Thindeck.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the thindeck.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.thindeck.steps;

import com.google.common.base.Joiner;
import com.jcabi.manifests.Manifests;
import com.jcabi.ssh.SSH;
import com.jcabi.ssh.Shell;
import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import javax.validation.constraints.NotNull;
import org.apache.commons.io.FileUtils;

/**
 * Nginx load balancer.
 * Assumption is that ngnix configuration loaded from ngnix.conf and each host
 * has a conf file named www.example.com.hosts.conf which will contain load
 * balancing group e.g:
 * <pre>
 * upstream example_servers {
 *     server 10.0.0.1:80;
 *     server 10.0.0.2:80;
 * }
 * </pre>
 * and a www.example.com.main.conf which will contain server configuration e.g.:
 * <pre>
 *
 * server {
 *     listen 80;
 *     server_name www.example.com;
 *     location / {
 *         proxy_pass http://example_servers;
 *     }
 * }
 * </pre>
 * those files are loaded from main ngnix.conf file using include directive.
 * @author Krzysztof Krason (Krzysztof.Krason@gmail.com)
 * @version $Id$
 * @todo #345 When a *hosts.conf file is created for a new host, it should be
 *  included in nginx.conf so that it can be loaded by the Nginx server. See
 *  the explanation in in https://github.com/yegor256/thindeck/issues/347
 *  for more details.
 * @todo #345 Let's handle the file *main.conf, which should contain the server
 *  configuration for a given host. The file name is prefixed by the host name,
 *  e.g. the host "www.example.com" will have the file name
 *  "www.example.com.hosts.conf". If the file doesn't exist yet, we should
 *  create it and include it in nginx.conf. If it already exists, we should
 *  update it. See the Javadoc above or the explanation in
 *  https://github.com/yegor256/thindeck/issues/347 for more details.
 *  @checkstyle MultipleStringLiterals (300 lines)
 */
public final class Nginx implements LoadBalancer {

    /**
     * Nginx binary name.
     */
    private final transient String binary;

    /**
     * Constructor.
     * @param bin Nginx binary name.
     */
    public Nginx(final String bin) {
        this.binary = bin;
    }

    /**
     * Default constructor.
     */
    public Nginx() {
        this("nginx");
    }

    /**
     * See {@link LoadBalancer#update(String, int, String, int)}.
     * @param host The host name indicated by requests
     * @param hport Port corresponding to the host name
     * @param server Server name to redirect requests to
     * @param sport Server port to redirect requests to
     * @checkstyle ParameterNumber (4 lines)
     */
    @Override
    public void update(@NotNull final String host, @NotNull final int hport,
        @NotNull final String server, @NotNull final int sport) {
        try {
            new Shell.Plain(
                new SSH(
                    Manifests.read("Thindeck-LoadBalancer-Host"),
                    Integer.parseInt(
                        Manifests.read("Thindeck-LoadBalancer-Port")
                    ),
                    Manifests.read("Thindeck-LoadBalancer-User"),
                    FileUtils.readFileToString(
                        new File(
                            Manifests.read("Thindeck-LoadBalancer-Key-File")
                        )
                    )
                )
            ).exec(
                Joiner.on(";").join(
                    String.format(
                        "cd %s",
                        Manifests.read("Thindeck-LoadBalancer-Directory")
                    ),
                    updateHostsConfigurationScript(host, server, sport),
                    String.format("pkill -HUP -f %s", this.binary)
                )
            );
        } catch (final UnknownHostException ex) {
            throw new IllegalStateException(ex);
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Script for updating the *hosts.conf file.
     * @param host The host name indicated by requests
     * @param server Server name to redirect requests to
     * @param sport Server port to redirect requests to
     * @return Commands for updating hosts configuration.
     */
    private static String updateHostsConfigurationScript(final String host,
        final String server, final int sport) {
        return Joiner.on(";").join(
            String.format("if [ -f %s.hosts.conf ]", host),
            String.format(
                "then if [[ $(grep '%s:%d' %s.hosts.conf) != *%s:%d* ]]",
                server,
                sport,
                host,
                server,
                sport
            ),
            String.format(
                "then sed -i.bak -r 's/}/    server %s:%d;\\n}/' %s.hosts.conf",
                server,
                sport,
                host
            ),
            String.format("fi"),
            String.format("rm %s.hosts.conf.bak", host),
            String.format(
                "else printf %s > %s.hosts.conf",
                Joiner.on("\\n").join(
                    String.format("'upstream %s_servers {", host),
                    String.format("    server %s:%d;", server, sport),
                    "}'"
                ),
                host
            ),
            "fi"
        );
    }
}
