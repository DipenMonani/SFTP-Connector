/*
 * Copyright (c) 2023 Infosys Ltd.
 * Use of this source code is governed by MIT license that can be found in the LICENSE file
 * or at https://opensource.org/licenses/MIT
 */
package com.infosys.camundaconnectors.file.sftp.service.files;

import com.infosys.camundaconnectors.file.sftp.model.request.SFTPRequestData;
import com.infosys.camundaconnectors.file.sftp.model.response.Response;
import com.infosys.camundaconnectors.file.sftp.model.response.SFTPResponse;

import jakarta.validation.constraints.NotBlank;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.xfer.FileSystemFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UploadFileService implements SFTPRequestData {

  private static final Logger LOGGER = LoggerFactory.getLogger(UploadFileService.class);

  // Absolute path to the file on the LOCAL machine running the connector.
  // e.g.  D:/upload/Hello.txt  or  /home/user/files/Hello.txt
  @NotBlank private String localFilePath;

  /** Absolute path of the DESTINATION DIRECTORY on the SFTP server.
   *  e.g.  /upload  or  /home/sftpuser/backup */
  @NotBlank private String remoteDirectory;

  @NotBlank private String actionIfFileExists;
  @NotBlank private String createRemoteDirIfNotExists;

  @Override
  public Response invoke(SFTPClient sftpClient) {
    File localFile = new File(localFilePath);
    String remoteDir = remoteDirectory.replace("\\", "/").trim();

    try {
      if (!localFile.exists() || !localFile.isFile()) {
        throw new IOException("Local file not found: " + localFilePath);
      }

      boolean createDir = !createRemoteDirIfNotExists.equalsIgnoreCase("false");
      if (!pathExists(sftpClient, remoteDir)) {
        if (createDir) {
          sftpClient.mkdir(remoteDir);
          LOGGER.info("Remote directory created: {}", remoteDir);
        } else {
          throw new RuntimeException("Remote directory does not exist: " + remoteDir);
        }
      }

      String remoteFilePath = remoteDir + "/" + localFile.getName();

      if (pathExists(sftpClient, remoteFilePath)) {
        if (actionIfFileExists.equalsIgnoreCase("skip")) {
          LOGGER.info("Skipped — file already exists at {}", remoteFilePath);
          return new SFTPResponse<>("Skipped (file already exists at " + remoteFilePath + ")");
        } else if (actionIfFileExists.equalsIgnoreCase("replace")) {
          sftpClient.rm(remoteFilePath);
          LOGGER.info("Existing remote file removed for replacement: {}", remoteFilePath);
        } else {
          remoteFilePath = resolveRenamedPath(sftpClient, remoteDir, localFile.getName());
          LOGGER.info("File will be uploaded as: {}", remoteFilePath);
        }
      }

      // Upload local file → SFTP server
      sftpClient.put(new FileSystemFile(localFile), remoteFilePath);
      LOGGER.info("Uploaded '{}' → '{}'", localFilePath, remoteFilePath);
      return new SFTPResponse<>("File uploaded successfully to " + remoteFilePath);

    } catch (Exception e) {
      throw new RuntimeException(e.getLocalizedMessage());
    } finally {
      try {
        if (sftpClient != null) sftpClient.close();
      } catch (IOException e) {
        LOGGER.error("Error closing SFTPClient");
      }
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private boolean pathExists(SFTPClient sftpClient, String path) {
    try {
      return sftpClient.stat(path) != null;
    } catch (Exception e) {
      return false;
    }
  }

  private String resolveRenamedPath(SFTPClient sftpClient, String dir, String fileName) {
    int dot     = fileName.lastIndexOf('.');
    String base = dot > 0 ? fileName.substring(0, dot)        : fileName;
    String ext  = dot > 0 ? "." + fileName.substring(dot + 1) : "";
    String candidate = dir + "/" + base + " - Copy" + ext;
    int count = 2;
    while (pathExists(sftpClient, candidate)) {
      candidate = dir + "/" + base + "(" + count + ")" + ext;
      count++;
    }
    return candidate;
  }

  // ── boilerplate ───────────────────────────────────────────────────────────

  public String getLocalFilePath() { return localFilePath; }
  public void setLocalFilePath(String localFilePath) { this.localFilePath = localFilePath; }

  public String getRemoteDirectory() { return remoteDirectory; }
  public void setRemoteDirectory(String remoteDirectory) { this.remoteDirectory = remoteDirectory; }

  public String getActionIfFileExists() { return actionIfFileExists; }
  public void setActionIfFileExists(String actionIfFileExists) { this.actionIfFileExists = actionIfFileExists; }

  public String getCreateRemoteDirIfNotExists() { return createRemoteDirIfNotExists; }
  public void setCreateRemoteDirIfNotExists(String v) { this.createRemoteDirIfNotExists = v; }

  @Override
  public int hashCode() {
    return Objects.hash(localFilePath, remoteDirectory, actionIfFileExists, createRemoteDirIfNotExists);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    UploadFileService o = (UploadFileService) obj;
    return Objects.equals(localFilePath, o.localFilePath)
        && Objects.equals(remoteDirectory, o.remoteDirectory)
        && Objects.equals(actionIfFileExists, o.actionIfFileExists)
        && Objects.equals(createRemoteDirIfNotExists, o.createRemoteDirIfNotExists);
  }

  @Override
  public String toString() {
    return "UploadFileService [localFilePath=" + localFilePath
        + ", remoteDirectory=" + remoteDirectory
        + ", actionIfFileExists=" + actionIfFileExists
        + ", createRemoteDirIfNotExists=" + createRemoteDirIfNotExists + "]";
  }
}
