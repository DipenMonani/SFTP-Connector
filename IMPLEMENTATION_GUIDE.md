# Infosys SFTP Connector with Camunda — Complete Implementation Guide

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Prerequisites](#2-prerequisites)
3. [Step 1 — Clone the Repository](#3-step-1--clone-the-repository)
4. [Step 2 — Install Element Template in Camunda Modeler](#4-step-2--install-element-template-in-camunda-modeler)
5. [Step 3 — Build the Connector JAR](#5-step-3--build-the-connector-jar)
6. [Step 4 — Run the Connector Runtime](#6-step-4--run-the-connector-runtime)
7. [Step 5 — Configure Your SFTP Server with Docker](#7-step-5--configure-your-sftp-server-with-docker)
8. [Step 6 — Generate the known_hosts File](#8-step-6--generate-the-known_hosts-file)
9. [Step 7 — Create a BPMN Diagram](#9-step-7--create-a-bpmn-diagram)
10. [All Operations Reference](#10-all-operations-reference)
11. [Output Mapping Reference](#11-output-mapping-reference)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                     CAMUNDA PLATFORM 8.9                            │
│                                                                     │
│  ┌──────────────┐      ┌──────────────────┐      ┌──────────────┐  │
│  │  Camunda     │      │  Zeebe Engine    │      │  Operate /   │  │
│  │  Modeler     │─────>│  (Orchestrator)  │<─────│  Tasklist    │  │
│  │  (.bpmn)     │      │                  │      │  (Monitor)   │  │
│  └──────────────┘      └────────┬─────────┘      └──────────────┘  │
│                                 │  Job: com.infosys.camundaconnectors│
│                                 │        .files:sftp:1              │
│                                 ▼                                   │
│                    ┌────────────────────────┐                       │
│                    │  Connector Runtime     │                       │
│                    │  (Spring Boot App)     │                       │
│                    │                        │                       │
│                    │  connector-sftp-       │                       │
│                    │  0.1.0-SNAPSHOT-       │                       │
│                    │  with-dependencies.jar │                       │
│                    │                        │                       │
│                    │  SFTPFunction.java     │                       │
│                    └──────────┬─────────────┘                       │
└───────────────────────────────┼─────────────────────────────────────┘
                                │  SSH/SFTP (Port 22)
                                ▼
                   ┌────────────────────────┐
                   │  SFTP Server           │
                   │  (Docker: atmoz/sftp)  │
                   │  or any SSH server     │
                   └────────────────────────┘
```

### Communication Flow

```
BPMN Task
  │
  │  1. Zeebe Engine picks up Service Task job
  ▼
Connector Runtime (polls Zeebe for jobs)
  │
  │  2. Deserializes input: host, port, username, password, operation, data
  ▼
SFTPFunction.java
  │
  │  3. Opens SSH/SFTP session via sshj library
  ▼
SFTP Server (port 22)
  │
  │  4. Executes file operation (copy/move/read/write/list/delete...)
  ▼
SFTPFunction returns result
  │
  │  5. Connector Runtime completes job → result stored in process variable
  ▼
Zeebe Engine continues process
```

---

## 2. Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| Java (JDK) | 11 or higher | `java -version` to verify |
| Apache Maven | 3.8+ | `mvn -version` to verify |
| Camunda Modeler | 5.x | Desktop app for BPMN design |
| Camunda Platform (Self-Managed) | **8.9** | Or Camunda SaaS |
| Docker | 20.x+ | For running the SFTP server |
| OpenSSH Client | Any | For generating `known_hosts` |
| Git | Any | For cloning the repo |

---

## 3. Step 1 — Clone the Repository

```bash
git clone https://github.com/Infosys/camunda-connectors.git
cd camunda-connectors/connector-sftp
```

Or if you already have the project locally (as in this repo):

```
D:\Projects\Test\Code\SFTP-Connector\
```

**Project structure after clone:**

```
connector-sftp/
├── element-templates/
│   └── sftp-file-connector.json        ← element template for Modeler
├── src/
│   ├── main/java/com/infosys/camundaconnectors/file/sftp/
│   │   ├── SFTPFunction.java           ← main connector entry point
│   │   ├── SFTPRequestDeserializer.java
│   │   ├── model/
│   │   │   ├── request/
│   │   │   └── response/
│   │   ├── service/
│   │   │   ├── files/                  ← file operations
│   │   │   └── folders/                ← folder operations
│   │   └── utility/
│   └── test/
├── assets/images/                      ← screenshots for each operation
├── pom.xml
└── README.md
```

---

## 4. Step 2 — Install Element Template in Camunda Modeler

The element template defines the SFTP connector UI panel inside the Camunda Modeler.

### 4.1 Locate the template file

```
element-templates\sftp-file-connector.json
```

### 4.2 Copy to Modeler resources folder

Copy `sftp-file-connector.json` to the Camunda Modeler element templates directory:

| OS | Path |
|---|---|
| Windows | `C:\Users\<YourUser>\AppData\Roaming\camunda-modeler\resources\element-templates\` |
| macOS | `~/Library/Application Support/camunda-modeler/resources/element-templates/` |
| Linux | `~/.config/camunda-modeler/resources/element-templates/` |

> **Tip:** If the `element-templates` folder does not exist, create it manually.

### 4.3 Restart Camunda Modeler

After copying the file, restart Camunda Modeler. The "SFTP File Connector" template will appear in the element template picker.

---

## 5. Step 3 — Build the Connector JAR

### 5.1 Build with Maven

From the `connector-sftp` project root:

```bash
mvn clean package
```

### 5.2 Expected output

After a successful build, the following files appear under `target/`:

```
target/
├── connector-sftp-0.1.0-SNAPSHOT.jar                    ← thin JAR (no deps)
├── connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar  ← fat JAR (use this)
└── lib/
    ├── bcprov-jdk15on-1.70.jar      ← BouncyCastle (kept separate, signed)
    ├── bcpkix-jdk15on-1.70.jar
    └── bcutil-jdk15on-1.70.jar
```

> **Important:** Always use `connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar` when running the connector runtime.

---

## 6. Step 4 — Get the Connector Runtime & Run

The **Camunda Connector Runtime** is a standalone Spring Boot application that hosts your connector JAR as a Zeebe Job Worker. It polls Zeebe for jobs of type `com.infosys.camundaconnectors.files:sftp:1`.

---

### 6.1 Get the Connector Runtime JAR

You need `connector-runtime-application-8.3.1-with-dependencies.jar`. Choose one method:

#### Method A — Download via Maven command (recommended)

Run this once from the project root to download the runtime JAR directly:

```bash
mvn dependency:copy "-Dartifact=io.camunda.connector:connector-runtime-application:8.3.1:jar:with-dependencies" "-DoutputDirectory=."
```

This places `connector-runtime-application-8.3.1-with-dependencies.jar` in the current directory.

#### Method B — Add to pom.xml (auto-download during build)

Add the following plugin inside the `<plugins>` section of `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-dependency-plugin</artifactId>
    <executions>
        <execution>
            <id>download-connector-runtime</id>
            <phase>prepare-package</phase>
            <goals>
                <goal>copy</goal>
            </goals>
            <configuration>
                <artifact>io.camunda.connector:connector-runtime-application:8.3.1:jar:with-dependencies</artifact>
                <outputDirectory>${project.basedir}</outputDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Then run:

```bash
mvn clean package -DskipTests
```

The runtime JAR is downloaded to the project root automatically every build.

#### Method C — Already present in this project

The file `connector-runtime-application-8.3.1-with-dependencies.jar` is already included in the project root. No download needed.

---

### 6.2 Start the Connector Runtime

Run the Camunda Connector Runtime and include the SFTP connector JAR in the classpath:

**Windows (PowerShell):**

```powershell
java -cp "connector-runtime-application-8.3.1-with-dependencies.jar;target\connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar" `
     io.camunda.connector.runtime.app.ConnectorRuntimeApplication `
     --zeebe.client.broker.gateway-address=localhost:26500 `
     --zeebe.client.security.plaintext=true
```

**Windows (Command Prompt):**

```cmd
java -cp "connector-runtime-application-8.3.1-with-dependencies.jar;target\connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar" ^
     io.camunda.connector.runtime.app.ConnectorRuntimeApplication ^
     --zeebe.client.broker.gateway-address=localhost:26500 ^
     --zeebe.client.security.plaintext=true
```

**Linux / macOS:**

```bash
java -cp "connector-runtime-application-8.3.1-with-dependencies.jar:target/connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar" \
     io.camunda.connector.runtime.app.ConnectorRuntimeApplication \
     --zeebe.client.broker.gateway-address=localhost:26500 \
     --zeebe.client.security.plaintext=true
```

**Expected startup output:**

```
Started ConnectorRuntimeApplication in X.XXX seconds
Registered connector: com.infosys.camundaconnectors.files:sftp:1
```

> Keep this process running while executing BPMN processes. To stop: **Ctrl+C**.

---

### 6.3 Rebuild after code changes

Every time you modify any Java source file, rebuild BEFORE restarting the runtime:

```bash
# Step 1 — Stop the running runtime (Ctrl+C)

# Step 2 — Rebuild
mvn clean package -DskipTests

# Step 3 — Restart (same command as 6.2)
```

> `mvn compile` alone does NOT update the fat JAR. Always use `mvn clean package`.

---

## 7. Step 5 — Configure Your SFTP Server with Docker

Using Docker is the fastest way to get a fully working SFTP server for local development and testing.

### 7.1 Required credentials (used in BPMN later)

| Parameter | Docker example value | Description |
|---|---|---|
| `host` | `localhost` | Hostname or IP of the SFTP server |
| `portNumber` | `22` | SSH/SFTP port |
| `username` | `sftpuser` | SFTP login username |
| `password` | `sftppass` | SFTP login password (store as Camunda Secret) |
| `knownHostsPath` | `C:/Users/DELL/.ssh/known_hosts` | Path to `known_hosts` on the machine running the connector |

---

### 7.2 Pull the SFTP Docker image

```bash
docker pull atmoz/sftp
```

---

### 7.3 Run the SFTP container (basic)

```bash
docker run -d \
  --name sftp-server \
  -p 22:22 \
  atmoz/sftp \
  sftpuser:sftppass:::uploads
```

**Format:** `username:password:[uid]:[gid]:directory`

- `sftpuser` — login username
- `sftppass` — login password
- `uploads` — home subdirectory created inside `/home/sftpuser/uploads`

---

### 7.4 Run with a persistent local volume (recommended)

Files written by the connector will survive container restarts.

**Windows (PowerShell):**

```powershell
docker run -d `
  --name sftp-server `
  -p 22:22 `
  -v C:\sftp-data:/home/sftpuser/uploads `
  atmoz/sftp `
  sftpuser:sftppass:::uploads
```

**Linux / macOS:**

```bash
docker run -d \
  --name sftp-server \
  -p 22:22 \
  -v $(pwd)/sftp-data:/home/sftpuser/uploads \
  atmoz/sftp \
  sftpuser:sftppass:::uploads
```

---

### 7.5 Run with Docker Compose

Create a `docker-compose.yml` file:

```yaml
version: '3.8'

services:
  sftp-server:
    image: atmoz/sftp
    container_name: sftp-server
    ports:
      - "22:22"
    volumes:
      - ./sftp-data:/home/sftpuser/uploads
    command: sftpuser:sftppass:::uploads
    restart: unless-stopped
```

Start the service:

```bash
docker compose up -d
```

Stop the service:

```bash
docker compose down
```

---

### 7.6 Verify the SFTP container is running

```bash
# Check container status
docker ps

# Expected output:
# CONTAINER ID   IMAGE        COMMAND                  STATUS         PORTS                NAMES
# abc123def456   atmoz/sftp   "/entrypoint sftpuse…"   Up 2 minutes   0.0.0.0:22->22/tcp   sftp-server
```

View container logs:

```bash
docker logs sftp-server
docker logs -f sftp-server    # follow live logs
```

---

### 7.7 Test SFTP connection manually

```bash
# Connect via SFTP client
sftp -P 22 sftpuser@localhost

# Once connected, test basic commands:
sftp> pwd
sftp> ls
sftp> cd uploads
sftp> put localfile.txt         # upload a file
sftp> ls                        # confirm upload
sftp> get uploads/localfile.txt # download a file
sftp> bye
```

Or via SSH:

```bash
ssh -p 22 sftpuser@localhost
```

---

### 7.8 Create a test file inside the container

```bash
# Write a test file directly inside the container
docker exec -it sftp-server bash -c \
  "echo 'Hello SFTP Test' > /home/sftpuser/uploads/test.txt"

# Verify the file exists
docker exec -it sftp-server ls /home/sftpuser/uploads/
```

---

### 7.9 Manage the container

```bash
# Stop the container
docker stop sftp-server

# Start again
docker start sftp-server

# Restart
docker restart sftp-server

# Remove the container (data in ./sftp-data volume persists)
docker rm -f sftp-server

# Remove container and volume together
docker rm -f sftp-server && rm -rf ./sftp-data
```

---

### 7.10 Use a custom port (if port 22 is occupied)

If port 22 is already in use on your machine, map to a different host port:

```bash
docker run -d \
  --name sftp-server \
  -p 2222:22 \
  -v $(pwd)/sftp-data:/home/sftpuser/uploads \
  atmoz/sftp \
  sftpuser:sftppass:::uploads
```

Then in the BPMN authentication, set `portNumber` to `2222`.

---

### 7.11 Multiple SFTP users

Pass multiple `user:pass:::dir` entries separated by spaces:

```bash
docker run -d \
  --name sftp-server \
  -p 22:22 \
  atmoz/sftp \
  alice:alicepass:::uploads bob:bobpass:::exports
```

---

### 7.12 Store SFTP password as a Camunda Secret

Instead of hardcoding the password in the BPMN diagram, use Camunda Secrets.

**Self-Managed — set as an environment variable on the connector runtime process:**

```bash
# Windows
set CONNECTOR_SECRET_SFTP_PASSWORD=sftppass

# Linux / macOS
export CONNECTOR_SECRET_SFTP_PASSWORD=sftppass
```

Then reference it in the BPMN task authentication as:

```
Password: secrets.SFTP_PASSWORD
```

**Camunda SaaS — add via Console:**  
Go to **Console > Cluster > Secrets > Add Secret** → name `SFTP_PASSWORD`, value `sftppass`

---

## 8. Step 6 — Generate the known_hosts File

The `known_hosts` file stores the SFTP server's public key fingerprint, preventing man-in-the-middle attacks.

### 8.1 Generate using ssh-keyscan

```bash
# For localhost (default port 22)
ssh-keyscan -t rsa localhost >> C:\Users\DELL\.ssh\known_hosts

# For a remote host
ssh-keyscan -t rsa 192.168.1.100 >> C:\Users\DELL\.ssh\known_hosts

# For a custom port (e.g. 2222)
ssh-keyscan -p 2222 -t rsa localhost >> C:\Users\DELL\.ssh\known_hosts
```

> **Docker note:** Run `ssh-keyscan` after the container is running, since the container generates its own host key on first start.

### 8.2 Verify the file was created

```bash
# Windows
type C:\Users\DELL\.ssh\known_hosts

# Linux / macOS
cat ~/.ssh/known_hosts
```

Expected output (one line per server):

```
localhost ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAB...
```

### 8.3 Use this path in the connector

In the BPMN task configuration, set:

```
Known Hosts Path: C:/Users/DELL/.ssh/known_hosts
```

> Use forward slashes `/` in the path even on Windows.

---

## 9. Step 7 — Create a BPMN Diagram

### 9.1 Open Camunda Modeler

Launch Camunda Modeler and create a new BPMN diagram: **File > New File > BPMN Diagram**

### 9.2 Add a Service Task and apply the SFTP template

1. Drag a **Task** element onto the canvas
2. Click the wrench icon (Change type) → select **SFTP File Connector**  
   OR right-click → **Append Connector** → select **SFTP File Connector**
3. The task now shows the Infosys SFTP icon

### 9.3 Configure Authentication section

In the **Properties Panel** on the right, fill the **Authentication** group:

| Field | Value |
|---|---|
| Host | `localhost` |
| Port | `22` |
| Username | `sftpuser` |
| Password | `secrets.SFTP_PASSWORD` |
| Known Hosts Path | `C:/Users/DELL/.ssh/known_hosts` |

### 9.4 Select the Operation

Choose **Write File** from the Operation dropdown for this example.

| Operation Value | Description |
|---|---|
| `sftp.write-file` | Write/append content to a remote file |
| `sftp.read-file` | Read contents of a remote file |
| `sftp.list-files` | List files in a remote directory |
| `sftp.list-folders` | List folders in a remote directory |
| `sftp.copy-file` | Copy a file to another location |
| `sftp.copy-folder` | Copy a folder to another location |
| `sftp.move-file` | Move a file to another location |
| `sftp.move-folder` | Move a folder to another location |
| `sftp.delete-file` | Delete a file |
| `sftp.delete-folder` | Delete a folder |
| `sftp.create-folder` | Create a new folder |

### 9.5 Example: Write File operation — full configuration

This writes a file to the remote SFTP server via the connector.

**Authentication panel:**

```
Host:             localhost
Port:             22
Username:         sftpuser
Password:         secrets.SFTP_PASSWORD
Known Hosts Path: C:/Users/DELL/.ssh/known_hosts
```

**Input Mapping panel:**

```
Operation:   Write File
File Path:   /home/sftpuser/uploads/output.txt
Content:     Hello from Camunda 8.9!
```

**Output Mapping panel:**

```
Result Variable:    sftpResult
Result Expression:  = { writeStatus: response.result.response }
```

**Expected result stored in `sftpResult`:**

```json
{
  "writeStatus": "File written successfully"
}
```

**Verify on the Docker SFTP server:**

```bash
docker exec -it sftp-server cat /home/sftpuser/uploads/output.txt
# Output: Hello from Camunda 8.9!
```

### 9.6 Sample BPMN diagram layout

```
[Start Event] ──► [Write File via SFTP] ──► [End Event]
```

### 9.7 Save the BPMN file

**File > Save As** → save as `sftp-write-process.bpmn`

---

## 10. All Operations Reference

### Authentication (required for all operations)

```json
{
  "authentication": {
    "hostname": "localhost",
    "portNumber": "22",
    "username": "sftpuser",
    "password": "secrets.SFTP_PASSWORD",
    "knownHostsPath": "C:/Users/DELL/.ssh/known_hosts"
  }
}
```

---

### Write File

Writes (appends) content to a file on the remote server.

```json
{
  "operation": "sftp.write-file",
  "data": {
    "filePath": "/home/sftpuser/uploads/output.txt",
    "content": "Hello from Camunda 8.9!"
  }
}
```

**Verify result on Docker server:**

```bash
docker exec -it sftp-server cat /home/sftpuser/uploads/output.txt
```

---

### Read File

Reads the contents of a file on the remote server.

```json
{
  "operation": "sftp.read-file",
  "data": {
    "sourceFilePath": "/home/sftpuser/uploads/output.txt"
  }
}
```

---

### Copy File

```json
{
  "operation": "sftp.copy-file",
  "data": {
    "sourceFilePath": "/home/sftpuser/uploads/demo.txt",
    "targetDirectory": "/home/sftpuser/uploads/backup",
    "actionIfFileExists": "rename",
    "createNewFolderIfNotExists": "True"
  }
}
```

| Field | Values | Description |
|---|---|---|
| `actionIfFileExists` | `rename` / `replace` / `skip` | What to do if target file already exists |
| `createNewFolderIfNotExists` | `True` / `False` | Create target directory if it doesn't exist |

---

### Copy Folder

```json
{
  "operation": "sftp.copy-folder",
  "data": {
    "sourceDirectory": "/home/sftpuser/uploads/sourceFolder",
    "targetDirectory": "/home/sftpuser/uploads/targetFolder",
    "actionIfFileExists": "rename"
  }
}
```

---

### Move File

```json
{
  "operation": "sftp.move-file",
  "data": {
    "sourceFilePath": "/home/sftpuser/uploads/demo.txt",
    "targetDirectory": "/home/sftpuser/uploads/archive",
    "actionIfFileExists": "replace"
  }
}
```

---

### Move Folder

```json
{
  "operation": "sftp.move-folder",
  "data": {
    "sourceDirectory": "/home/sftpuser/uploads/sourceFolder",
    "targetDirectory": "/home/sftpuser/uploads/archiveFolder",
    "actionIfFileExists": "replace"
  }
}
```

---

### Delete File

```json
{
  "operation": "sftp.delete-file",
  "data": {
    "folderPath": "/home/sftpuser/uploads",
    "fileName": "demo.txt"
  }
}
```

---

### Delete Folder

```json
{
  "operation": "sftp.delete-folder",
  "data": {
    "folderPath": "/home/sftpuser/uploads/myFolder"
  }
}
```

---

### Create Folder

```json
{
  "operation": "sftp.create-folder",
  "data": {
    "folderPath": "/home/sftpuser/uploads",
    "newFolderName": "NewFolder",
    "actionIfFileExists": "rename"
  }
}
```

---

### List Files

```json
{
  "operation": "sftp.list-files",
  "data": {
    "filePath": "/home/sftpuser/uploads",
    "fileNamePattern": "*.txt",
    "modifiedBefore": "31-3-2025 12:0:0",
    "modifiedAfter": "1-1-2025 12:0:0",
    "searchSubFoldersAlso": "True",
    "maxNumberOfFiles": "10",
    "maxDepth": "2",
    "outputType": "filePaths",
    "sortBy": {
      "sortOn": "date",
      "order": "desc"
    }
  }
}
```

| `outputType` value | Response content |
|---|---|
| `filePaths` | List of file names only |
| `fileDetails` | List of maps with name, size, parent, path, etc. |

| `sortOn` values | Description |
|---|---|
| `name` | Sort by filename |
| `size` | Sort by file size |
| `date` | Sort by last modified date |
| `last accessed date` | Sort by last accessed date |

---

### List Folders

```json
{
  "operation": "sftp.list-folders",
  "data": {
    "folderPath": "/home/sftpuser/uploads",
    "namePattern": "backup*",
    "modifiedBefore": "31-3-2025 12:0:0",
    "modifiedAfter": "1-1-2025 12:0:0",
    "searchSubFoldersAlso": "False",
    "maxNumberOfFiles": "5",
    "maxDepth": "1",
    "outputType": "folderPaths",
    "sortBy": {
      "sortOn": "name",
      "order": "asc"
    }
  }
}
```

| `outputType` value | Response content |
|---|---|
| `folderPaths` | List of folder names only |
| `folderDetails` | List of maps with folder attribute details |

---

## 11. Output Mapping Reference

The connector returns results in this structure:

```json
{
  "result": {
    "response": "..."
  }
}
```

### Mapping examples in BPMN properties panel

**Store full result:**

```
Result Variable: sftpResult
```

**Extract write status (Write File):**

```
Result Expression: = { writeStatus: response.result.response }
```

**Extract file list (List Files):**

```
Result Expression: = { fileNames: response.result.response }
```

**Check operation status:**

```
Result Expression: = { status: response.result.response }
```

---

## 12. Troubleshooting

### Connector runtime does not start

- Verify Java 11+: `java -version`
- Ensure you are running the `-with-dependencies.jar` (fat JAR), not the thin JAR
- Check that Zeebe is reachable at `localhost:26500`

### Job never picked up (stuck in Zeebe)

- Confirm the connector runtime is running and shows "Registered connector" in logs
- Verify the BPMN service task type matches exactly: `com.infosys.camundaconnectors.files:sftp:1`
- Check network connectivity to Zeebe port `26500`

### Docker SFTP container not accessible

```bash
# Check container is running
docker ps | grep sftp-server

# Check port binding
docker port sftp-server

# Check container logs for errors
docker logs sftp-server

# Test SSH connectivity directly
ssh -p 22 -o StrictHostKeyChecking=no sftpuser@localhost
```

### Authentication failed / Connection refused

- Verify host, port, username, and password match your Docker run command
- Test the connection directly before running the BPMN:
  ```bash
  sftp -P 22 sftpuser@localhost
  ```
- Regenerate `known_hosts` after recreating the container (container generates a new host key):
  ```bash
  ssh-keyscan -t rsa localhost > C:/Users/DELL/.ssh/known_hosts
  ```

### known_hosts error

```
com.hierynomus.sshj.userauth.UserAuthException: ... known_hosts
```

- The Docker container may have been recreated with a new host key — regenerate `known_hosts`:
  ```bash
  ssh-keyscan -t rsa localhost > C:/Users/DELL/.ssh/known_hosts
  ```
- Use forward slashes in the path: `C:/Users/DELL/.ssh/known_hosts`
- Ensure the connector runtime process has read access to the `known_hosts` file

### File not found on SFTP server

- Paths inside the Docker container start at `/home/sftpuser/uploads/`
- Verify the path using:
  ```bash
  docker exec -it sftp-server ls /home/sftpuser/uploads/
  ```

### Secrets not resolved (`secrets.SFTP_PASSWORD` stays as-is)

- Set the environment variable on the connector runtime process before starting it:
  ```bash
  set CONNECTOR_SECRET_SFTP_PASSWORD=sftppass
  java -jar connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar
  ```
- The secret name in BPMN (`secrets.SFTP_PASSWORD`) must match the env var suffix (`SFTP_PASSWORD`)

### Build failure: `mvn clean package`

- Ensure Maven has access to Maven Central (check proxy/firewall settings)
- Run `mvn dependency:resolve` to isolate download errors
- Java version must be 11+: set `JAVA_HOME` to a JDK 11+ installation path

---

## Quick Reference Checklist

```
[ ] Java 11+ installed
[ ] Maven 3.8+ installed
[ ] Repository cloned
[ ] sftp-file-connector.json copied to Modeler element-templates folder
[ ] Modeler restarted
[ ] mvn clean package ran successfully
[ ] connector-sftp-0.1.0-SNAPSHOT-with-dependencies.jar built
[ ] Docker installed and running
[ ] SFTP Docker container started (docker run atmoz/sftp ...)
[ ] SFTP container verified (docker ps, sftp sftpuser@localhost)
[ ] known_hosts file generated with ssh-keyscan after container start
[ ] SFTP password stored as Camunda Secret (CONNECTOR_SECRET_SFTP_PASSWORD)
[ ] Connector runtime started (java -jar connector-sftp-...jar)
[ ] BPMN diagram created with SFTP File Connector task
[ ] Authentication fields filled (host, port, username, password, known_hosts)
[ ] Write File operation configured (filePath + content)
[ ] Result Variable set for output capture
[ ] File written to SFTP server verified (docker exec cat /home/sftpuser/uploads/...)
```
