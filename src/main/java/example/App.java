package example;

import org.apache.jclouds.ContextBuilder;
import org.apache.jclouds.blobstore.BlobStore;
import org.apache.jclouds.blobstore.BlobStoreContext;

import org.gaul.s3proxy.S3Proxy;
import org.gaul.s3proxy.AuthenticationType;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class App {
  public static void main(String[] args) throws Exception {
    String endpoint = "http://127.0.0.1:9090";
    String bucket = "test-bucket";
    String key = "hello/hello.txt";

    // Where objects will actually be stored on disk:
    Path baseDir = Path.of("target", "s3proxy-data").toAbsolutePath();
    Files.createDirectories(baseDir);

    // 1) Build a jclouds BlobStore backed by the local filesystem.
    Properties jcloudsProps = new Properties();
    jcloudsProps.setProperty("jclouds.filesystem.basedir", baseDir.toString());

    BlobStoreContext ctx = ContextBuilder.newBuilder("filesystem")
        .credentials("ignored", "ignored")
        .overrides(jcloudsProps)
        .buildView(BlobStoreContext.class);

    BlobStore blobStore = ctx.getBlobStore();

    // 2) Start S3Proxy with anonymous auth.
    S3Proxy s3Proxy = S3Proxy.builder()
        .endpoint(URI.create(endpoint))
        .awsAuthentication(AuthenticationType.NONE, null, null)
        .blobStore(blobStore)
        .build();

    s3Proxy.start();
    System.out.println("S3Proxy started on " + endpoint);
    System.out.println("Backing store: " + baseDir);

    // Ensure S3Proxy is always stopped.
    try {
      // 3) Create an S3 client pointing at S3Proxy.
      S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create(endpoint))
          .region(Region.US_EAST_1)
          .credentialsProvider(AnonymousCredentialsProvider.create())
          .serviceConfiguration(S3Configuration.builder()
              .pathStyleAccessEnabled(true) // important for most local S3 endpoints
              .build())
          .build();

      // 4) Create bucket.
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      System.out.println("Created bucket: " + bucket);

      // 5) Write a local file and upload it.
      Path file = Path.of("target", "upload.txt");
      Files.createDirectories(file.getParent());
      Files.writeString(file, "Hello from S3Proxy + filesystem!\n", StandardCharsets.UTF_8);

      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("text/plain")
              .build(),
          RequestBody.fromFile(file)
      );
      System.out.println("Uploaded: s3://" + bucket + "/" + key);

      // 6) List objects to prove it worked.
      ListObjectsV2Response listed = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build());
      System.out.println("Objects in bucket:");
      for (S3Object obj : listed.contents()) {
        System.out.println(" - " + obj.key() + " (" + obj.size() + " bytes)");
      }

      s3.close();
    } finally {
      s3Proxy.stop();
      ctx.close();
      System.out.println("S3Proxy stopped.");
      System.out.println("Files are on disk under: " + baseDir);
    }
  }
}
