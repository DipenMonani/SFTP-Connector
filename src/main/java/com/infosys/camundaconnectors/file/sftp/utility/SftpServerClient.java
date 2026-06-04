/*
 * Copyright (c) 2023 Infosys Ltd.
 * Use of this source code is governed by MIT license that can be found in the LICENSE file
 * or at https://opensource.org/licenses/MIT
 */
package com.infosys.camundaconnectors.file.sftp.utility;

import com.infosys.camundaconnectors.file.sftp.model.request.Authentication;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SftpServerClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(SftpServerClient.class);
  SSHClient client = null;

  public SSHClient loginSftp(final Authentication authentication) throws Exception {
    client = new SSHClient();
    client.addHostKeyVerifier(new PromiscuousVerifier());
    LOGGER.info("Connecting to server: " + authentication.getHostname());
    client.connect(authentication.getHostname(), Integer.parseInt(authentication.getPortNumber()));
    client.authPassword(authentication.getUsername(), authentication.getPassword());
    LOGGER.info("Connected to the server");
    return client;
  }

  public void logoutSftp() throws Exception {
    if (client != null) client.close();
  }
}
