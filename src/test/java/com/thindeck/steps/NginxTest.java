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
import com.google.common.io.Files;
import com.jcabi.aspects.Tv;
import com.jcabi.manifests.Manifests;
import com.jcabi.ssh.SSHD;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test case for {@link Nginx}.
 *
 * @author Krzysztof Krason (Krzysztof.Krason@gmail.com)
 * @version $Id$
 * @checkstyle MultipleStringLiterals (500 lines)
 */
public final class NginxTest {

    /**
     * The temporary directory.
     */
    private static File temp;

    /**
     * Set up.
     */
    @BeforeClass
    public static void setUp() {
        temp = Files.createTempDir();
    }

    /**
     * Tear down.
     * @throws Exception If something goes wrong.
     */
    @AfterClass
    public static void tearDown() throws Exception {
        FileUtils.deleteDirectory(temp);
    }

    /**
     * Ngnix can create host configuration.
     * @throws IOException In case of error.
     */
    @Test
    public void createsHostsConfiguration() throws IOException {
        Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
        final SSHD sshd = new SSHD(temp);
        sshd.start();
        final File key = File.createTempFile("ssh", "key", temp);
        FileUtils.write(key, sshd.key());
        this.manifest(temp, sshd.login(), sshd.port(), key);
        final String host = "host";
        final int sport = 567;
        final String server = "server";
        final File fhosts = this.hosts(temp, host);
        try {
            // @checkstyle MagicNumber (1 line)
            new Nginx().update(host, 1234, server, sport);
        } finally {
            sshd.stop();
        }
        MatcherAssert.assertThat(
            FileUtils.readFileToString(fhosts),
            Matchers.equalTo(
                Joiner.on('\n').join(
                    "upstream example_servers {",
                    "    server 10.0.0.1:80;",
                    "    server 10.0.0.2:80;",
                    String.format("    server %s:%d;", server, sport),
                    "}"
                )
            )
        );
    }

    /**
     * Nginx can create server.hosts.conf file.
     * @throws IOException If something goes wrong
     */
    @Test
    public void createsHostSpecificConfigurationFile() throws IOException {
        Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
        final SSHD sshd = new SSHD(temp);
        sshd.start();
        final File key = File.createTempFile("ssh", "key", temp);
        FileUtils.write(key, sshd.key());
        this.manifest(temp, sshd.login(), sshd.port(), key);
        final String host = "host2";
        final int sport = 456;
        final String server = "server2";
        sshd.start();
        try {
            new Nginx().update(host, Tv.THOUSAND, server, sport);
        } finally {
            sshd.stop();
        }
        MatcherAssert.assertThat(
            FileUtils.readFileToString(new File(temp, this.hostsConfig(host))),
            Matchers.equalTo(
                Joiner.on('\n').join(
                    String.format("upstream %s_servers {", host),
                    String.format("    server %s:%d;", server, sport),
                    "}"
                )
            )
        );
    }

    /**
     * Ngnix can reload configuration.
     * @throws Exception In case of error.
     * @checkstyle ExecutableStatementCountCheck (21 lines)
     */
    @Test
    public void reloadsConfiguration() throws Exception {
        Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
        final SSHD sshd = new SSHD(temp);
        sshd.start();
        final File key = File.createTempFile("ssh", "key", temp);
        FileUtils.write(key, sshd.key());
        this.manifest(temp, sshd.login(), sshd.port(), key);
        final String bin = String.format(
            "%s.sh", RandomStringUtils.randomAlphanumeric(128)
        );
        final File script = File.createTempFile("script", bin, temp);
        final File marker = File.createTempFile("marker", "temp", temp);
        FileUtils.writeStringToFile(
            script,
            Joiner.on("\n").join(
                "#!/bin/bash",
                "function sighup(){",
                String.format("    echo restarted > %s", marker.toString()),
                "    exit 0",
                "}",
                String.format("    echo running > %s", marker.toString()),
                "trap 'sighup' HUP",
                "sleep 30",
                String.format("    echo stopped > %s", marker.toString())
            )
        );
        final ProcessBuilder builder = new ProcessBuilder(
            "/bin/bash", script.toString()
        );
        builder.redirectInput(new File("/dev/null"));
        builder.redirectOutput(new File("/dev/null"));
        builder.redirectError(new File("/dev/null"));
        final Process process = builder.start();
        try {
            new Nginx(bin).update("", 1, "", 2);
            process.waitFor();
        } finally {
            sshd.stop();
        }
        MatcherAssert.assertThat(
            FileUtils.readFileToString(marker),
            Matchers.equalTo("restarted\n")
        );
    }

    /**
     * Ngnix retains host configuration if it already exists.
     * @throws IOException In case of error.
     */
    @Test
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    public void retainsExistingHostsConfiguration() throws IOException {
        Assume.assumeFalse(SystemUtils.IS_OS_WINDOWS);
        final SSHD sshd = new SSHD(temp);
        final File key = File.createTempFile("ssh2", "key2", temp);
        FileUtils.write(key, sshd.key());
        this.manifest(temp, sshd.login(), sshd.port(), key);
        final String host = "existing-host";
        final int sport = 80;
        final String server = "10.0.0.2";
        final File fhosts = this.hosts(temp, host);
        sshd.start();
        try {
            // @checkstyle MagicNumber (1 line)
            new Nginx().update(host, 1234, server, sport);
        } finally {
            sshd.stop();
        }
        MatcherAssert.assertThat(
            FileUtils.readFileToString(fhosts),
            Matchers.equalTo(
                Joiner.on('\n').join(
                    "upstream example_servers {",
                    "    server 10.0.0.1:80;",
                    "    server 10.0.0.2:80;",
                    "}"
                )
            )
        );
    }

    /**
     * Create hosts configuration file.
     * @param path Directory where to create file.
     * @param host Name of the host.
     * @return Location of created file.
     * @throws IOException In case of error.
     */
    private File hosts(final File path, final String host) throws IOException {
        final File fhosts = new File(
            path, this.hostsConfig(host)
        );
        FileUtils.writeStringToFile(
            fhosts,
            Joiner.on('\n').join(
                "upstream example_servers {",
                "    server 10.0.0.1:80;",
                "    server 10.0.0.2:80;",
                "}"
            )
        );
        return fhosts;
    }

    /**
     * Create mock manifest.
     * @param path Nginx directory.
     * @param login User performing update.
     * @param port SSH port to use.
     * @param key User private key file.
     * @throws IOException In case of error.
     * @checkstyle ParameterNumber (3 lines)
     */
    private void manifest(final File path, final String login, final int port,
        final File key) throws IOException {
        final String file = Joiner.on('\n').join(
            "Thindeck-LoadBalancer-Host: localhost",
            String.format("Thindeck-LoadBalancer-Port: %d", port),
            String.format("Thindeck-LoadBalancer-User: %s", login),
            String.format(
                "Thindeck-LoadBalancer-Key-File: %s", key.toString()
            ),
            String.format(
                "Thindeck-LoadBalancer-Directory: %s", path.toString()
            ),
            StringUtils.EMPTY
        );
        Manifests.append(new ByteArrayInputStream(file.getBytes()));
    }

    /**
     * File name for hosts config.
     * @param host The host
     * @return File name for hosts config.
     */
    private String hostsConfig(final String host) {
        return String.format("%s.hosts.conf", host);
    }
}
