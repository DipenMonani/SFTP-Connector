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

import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WriteFileService implements SFTPRequestData {
  private static final Logger LOGGER = LoggerFactory.getLogger(WriteFileService.class);
  @NotBlank private String filePath;
  private String content;
  SFTPResponse<?> writeFileResponse;

  public Response invoke(SFTPClient sftpClient) {
    if (filePath == null || filePath.isBlank() || filePath.isEmpty())
      throw new RuntimeException("Source file path is empty");
    String sourceFilePath = filePath.replace("\\", "/");
    try {
      byte[] contentInBytes = content != null ? content.getBytes("utf-8") : new byte[0];
      if (contentInBytes.length > 32766) {
        LOGGER.error("Content size is greater than 32766 byte");
        throw new RuntimeException("Content size should be less than 32766 byte");
      }
      // Ensure parent directories exist
      String parentDir = sourceFilePath.contains("/")
          ? sourceFilePath.substring(0, sourceFilePath.lastIndexOf('/'))
          : null;
      if (parentDir != null && !parentDir.isEmpty()) {
        sftpClient.mkdirs(parentDir);
      }

      boolean fileExists = checkIfFileExists(sftpClient, sourceFilePath);
      LOGGER.info("Writing file: {} (exists={})", sourceFilePath, fileExists);
      RemoteFile file;
      long fileOffset;
      if (fileExists) {
        file = sftpClient.open(sourceFilePath, EnumSet.of(OpenMode.WRITE));
        fileOffset = sftpClient.stat(sourceFilePath).getSize();
      } else {
        file = sftpClient.open(sourceFilePath, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
        fileOffset = 0;
      }
      file.write(fileOffset, contentInBytes, 0, contentInBytes.length);
      file.close();
      writeFileResponse = new SFTPResponse<>("File written successfully");
    } catch (IOException e) {
      throw new RuntimeException(e.getLocalizedMessage());
    } finally {
      try {
        if (sftpClient != null) sftpClient.close();
      } catch (IOException e) {
        LOGGER.error("Some Error occurred while trying to close the SFTPClient");
      }
    }
    return writeFileResponse;
  }

  public boolean checkIfFileExists(SFTPClient sftpClient, String newFilePath) {
    try {
      if (sftpClient.stat(newFilePath) == null) {
        return false;
      }
    } catch (Exception e) {
      return false;
    }
    return true;
  }

  public String getFilePath() {
    return filePath;
  }

  public void setFilePath(String filePath) {
    this.filePath = filePath;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  @Override
  public int hashCode() {
    return Objects.hash(content, filePath);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    WriteFileService other = (WriteFileService) obj;
    return Objects.equals(content, other.content) && Objects.equals(filePath, other.filePath);
  }

  @Override
  public String toString() {
    return "WriteFileService [filePath=" + filePath + ", content=" + content + "]";
  }
}
