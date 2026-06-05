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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.xfer.FileSystemFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

public class S3ToSftpService implements SFTPRequestData {

  private static final Logger LOGGER = LoggerFactory.getLogger(S3ToSftpService.class);

  @NotBlank private String s3BucketName;
  @NotBlank private String s3ObjectKey;
  @NotBlank private String awsRegion;

  // If blank the default credential chain is used
  // (env vars / instance profile / ECS task role)
  private String awsAccessKeyId;
  private String awsSecretAccessKey;

  @NotBlank private String remoteDirectory;
  @NotBlank private String actionIfFileExists;
  @NotBlank private String createRemoteDirIfNotExists;

  @Override
  public Response invoke(SFTPClient sftpClient) {
    String remoteDir  = remoteDirectory.replace("\\", "/").trim();
    String fileName   = extractFileName(s3ObjectKey);
    Path   tempDir    = null;
    Path   tempFile   = null;
    S3Client s3       = null;

    try {
      s3 = buildS3Client();

      // Download S3 object into a temp file named after the original key
      tempDir  = Files.createTempDirectory("s3-sftp-");
      tempFile = tempDir.resolve(fileName);

      LOGGER.info("Downloading s3://{}/{} …", s3BucketName, s3ObjectKey);
      try {
        s3.getObject(
            GetObjectRequest.builder().bucket(s3BucketName).key(s3ObjectKey).build(),
            tempFile);
      } catch (NoSuchKeyException e) {
        throw new RuntimeException("S3 object not found: s3://" + s3BucketName + "/" + s3ObjectKey);
      } catch (NoSuchBucketException e) {
        throw new RuntimeException("S3 bucket not found: " + s3BucketName);
      }
      LOGGER.info("Downloaded {} bytes from S3", Files.size(tempFile));

      // Ensure remote directory exists on SFTP
      boolean createDir = !createRemoteDirIfNotExists.equalsIgnoreCase("false");
      if (!pathExists(sftpClient, remoteDir)) {
        if (createDir) {
          sftpClient.mkdir(remoteDir);
          LOGGER.info("Remote directory created: {}", remoteDir);
        } else {
          throw new RuntimeException("Remote directory does not exist: " + remoteDir);
        }
      }

      String remoteFilePath = remoteDir + "/" + fileName;

      if (pathExists(sftpClient, remoteFilePath)) {
        if (actionIfFileExists.equalsIgnoreCase("skip")) {
          LOGGER.info("Skipped — file already exists at {}", remoteFilePath);
          return new SFTPResponse<>("Skipped (file already exists at " + remoteFilePath + ")");
        } else if (actionIfFileExists.equalsIgnoreCase("replace")) {
          sftpClient.rm(remoteFilePath);
          LOGGER.info("Removed existing remote file for replacement");
        } else {
          remoteFilePath = resolveRenamedPath(sftpClient, remoteDir, fileName);
          LOGGER.info("File will be uploaded as: {}", remoteFilePath);
        }
      }

      // Upload temp file → SFTP server
      sftpClient.put(new FileSystemFile(tempFile.toFile()), remoteFilePath);
      LOGGER.info("Uploaded '{}' → SFTP '{}'", s3ObjectKey, remoteFilePath);
      return new SFTPResponse<>("Transferred s3://" + s3BucketName + "/" + s3ObjectKey
          + " → " + remoteFilePath);

    } catch (Exception e) {
      throw new RuntimeException(e.getLocalizedMessage());
    } finally {
      // Clean up temp files
      safeDelete(tempFile);
      safeDelete(tempDir);
      if (s3 != null) s3.close();
      try {
        if (sftpClient != null) sftpClient.close();
      } catch (IOException e) {
        LOGGER.error("Error closing SFTPClient");
      }
    }
  }

  // ── AWS helpers ───────────────────────────────────────────────────────────

  private S3Client buildS3Client() {
    S3ClientBuilder builder = S3Client.builder()
        .region(Region.of(awsRegion))
        .httpClient(UrlConnectionHttpClient.builder().build());

    boolean hasKeys = awsAccessKeyId != null && !awsAccessKeyId.isBlank()
        && awsSecretAccessKey != null && !awsSecretAccessKey.isBlank();

    if (hasKeys) {
      builder.credentialsProvider(
          StaticCredentialsProvider.create(
              AwsBasicCredentials.create(awsAccessKeyId, awsSecretAccessKey)));
    } else {
      // Fall back to env vars, instance profile, ECS task role, etc.
      builder.credentialsProvider(DefaultCredentialsProvider.create());
    }

    return builder.build();
  }

  // ── SFTP helpers ──────────────────────────────────────────────────────────

  private boolean pathExists(SFTPClient sftpClient, String path) {
    try { return sftpClient.stat(path) != null; } catch (Exception e) { return false; }
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

  // ── general helpers ───────────────────────────────────────────────────────

  private String extractFileName(String key) {
    int last = key.lastIndexOf('/');
    return last >= 0 ? key.substring(last + 1) : key;
  }

  private void safeDelete(Path path) {
    if (path != null) {
      try { Files.deleteIfExists(path); } catch (IOException ignored) {}
    }
  }

  // ── boilerplate ───────────────────────────────────────────────────────────

  public String getS3BucketName()               { return s3BucketName; }
  public void setS3BucketName(String v)         { this.s3BucketName = v; }
  public String getS3ObjectKey()                { return s3ObjectKey; }
  public void setS3ObjectKey(String v)          { this.s3ObjectKey = v; }
  public String getAwsRegion()                  { return awsRegion; }
  public void setAwsRegion(String v)            { this.awsRegion = v; }
  public String getAwsAccessKeyId()             { return awsAccessKeyId; }
  public void setAwsAccessKeyId(String v)       { this.awsAccessKeyId = v; }
  public String getAwsSecretAccessKey()         { return awsSecretAccessKey; }
  public void setAwsSecretAccessKey(String v)   { this.awsSecretAccessKey = v; }
  public String getRemoteDirectory()            { return remoteDirectory; }
  public void setRemoteDirectory(String v)      { this.remoteDirectory = v; }
  public String getActionIfFileExists()         { return actionIfFileExists; }
  public void setActionIfFileExists(String v)   { this.actionIfFileExists = v; }
  public String getCreateRemoteDirIfNotExists() { return createRemoteDirIfNotExists; }
  public void setCreateRemoteDirIfNotExists(String v) { this.createRemoteDirIfNotExists = v; }

  @Override
  public int hashCode() {
    return Objects.hash(s3BucketName, s3ObjectKey, awsRegion, remoteDirectory,
        actionIfFileExists, createRemoteDirIfNotExists);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null || getClass() != obj.getClass()) return false;
    S3ToSftpService o = (S3ToSftpService) obj;
    return Objects.equals(s3BucketName, o.s3BucketName)
        && Objects.equals(s3ObjectKey, o.s3ObjectKey)
        && Objects.equals(awsRegion, o.awsRegion)
        && Objects.equals(remoteDirectory, o.remoteDirectory);
  }

  @Override
  public String toString() {
    return "S3ToSftpService [s3BucketName=" + s3BucketName
        + ", s3ObjectKey=" + s3ObjectKey
        + ", awsRegion=" + awsRegion
        + ", remoteDirectory=" + remoteDirectory + "]";
  }
}
