# S3Proxy Local File Store - Gzipped Content Example

This repository demonstrates a minimal example of using [S3Proxy](https://github.com/gaul/s3proxy) with a local filesystem backend to handle gzipped S3 objects with proper content-encoding metadata.

## Purpose

This project showcases:

- Setting up S3Proxy with a filesystem-backed blob store using jclouds
- Creating an S3 client that connects to the local S3Proxy instance
- Uploading gzip-compressed objects with `Content-Encoding: gzip` metadata
- Retrieving and decompressing gzipped objects via the S3 API
- **Handling checksum validation issues with gzipped content**

## The Problem

When running the application with the default configuration, there are checksum validation errors. This occurs because the AWS SDK calculates checksums on the **compressed** data during upload, but S3Proxy may attempt to validate checksums on the **decompressed** data, causing a mismatch.

## The Solution

**Uncommenting lines 75-76** in `src/main/java/example/App.java` fixes the checksum validation problem:

```java
.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
```

These settings ensure proper checksum handling when working with gzipped content, allowing the SDK and S3Proxy to correctly validate compressed data.

## Running the Example

Build and run the project:

```bash
mvn clean compile exec:java
```

The application will:

1. Create a bucket named `test-bucket`
2. Gzip the string "Hello from S3Proxy (gzipped via S3)!\n"
3. Upload it to `s3://test-bucket/hello/hello.txt` with `Content-Encoding: gzip`
4. Retrieve and decompress the object
5. Print the decompressed content
