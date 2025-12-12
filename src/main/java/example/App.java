package example;


import org.gaul.s3proxy.S3Proxy;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.gaul.s3proxy.AuthenticationType;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class App {

  public static void main(String[] args) throws Exception {
    String endpoint = "http://127.0.0.1:9090";
    String bucket = "test-bucket";
    String key = "hello/hello.txt";

    // Where S3Proxy stores objects on disk
    Path baseDir = Path.of("target", "s3proxy-data").toAbsolutePath();
    Files.createDirectories(baseDir);

    // ------------------------------------------------------------------
    // 1) Create a filesystem-backed BlobStore (jclouds)
    // ------------------------------------------------------------------
    Properties props = new Properties();
    props.setProperty("jclouds.filesystem.basedir", baseDir.toString());

    BlobStoreContext blobStoreContext = ContextBuilder
        .newBuilder("filesystem")
        .credentials("ignored", "ignored")
        .overrides(props)
        .buildView(BlobStoreContext.class);

    BlobStore blobStore = blobStoreContext.getBlobStore();

    // ------------------------------------------------------------------
    // 2) Start S3Proxy (anonymous auth)
    // ------------------------------------------------------------------
    S3Proxy s3Proxy = S3Proxy.builder()
        .endpoint(URI.create(endpoint))
        .awsAuthentication(AuthenticationType.NONE, null, null)
        .blobStore(blobStore)
        .build();

    s3Proxy.start();
    System.out.println("S3Proxy running at " + endpoint);
    System.out.println("Backing store: " + baseDir);

    try {
      // ----------------------------------------------------------------
      // 3) Create S3 client pointing at S3Proxy
      // ----------------------------------------------------------------
      S3Client s3 = S3Client.builder()
          .endpointOverride(URI.create(endpoint))
          .region(Region.US_EAST_1)
          .credentialsProvider(AnonymousCredentialsProvider.create())
          //.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
          //.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
          .build();

      // ----------------------------------------------------------------
      // 4) Create bucket
      // ----------------------------------------------------------------
      s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      System.out.println("Created bucket: " + bucket);

      // ----------------------------------------------------------------
      // 5) Gzip content in memory
      // ----------------------------------------------------------------
      byte[] original = "Hello from S3Proxy (gzipped via S3)!\n"
          .getBytes(StandardCharsets.UTF_8);

      byte[] gzipped;
      try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
           GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
        gzip.write(original);
        gzip.finish();
        gzipped = baos.toByteArray();
      }

      // ----------------------------------------------------------------
      // 6) Upload gzipped object with correct metadata
      // ----------------------------------------------------------------
      s3.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(key)
              .contentType("text/plain")
              .contentEncoding("gzip")
              .contentLength((long) gzipped.length)
              .build(),
          RequestBody.fromBytes(gzipped)
      );

      System.out.println("Uploaded: s3://" + bucket + "/" + key);

      // ----------------------------------------------------------------
      // 7) Retrieve object via S3 and decompress
      // ----------------------------------------------------------------
      try (ResponseInputStream<GetObjectResponse> response =
               s3.getObject(GetObjectRequest.builder()
                   .bucket(bucket)
                   .key(key)
                   .build())) {

        String encoding = response.response().contentEncoding();
        if (!"gzip".equalsIgnoreCase(encoding)) {
          throw new IllegalStateException("Expected gzip encoding, got: " + encoding);
        }

        byte[] decompressed;
        try (GZIPInputStream gis = new GZIPInputStream(response)) {
          decompressed = gis.readAllBytes();
        }

        String text = new String(decompressed, StandardCharsets.UTF_8);
        System.out.println("Retrieved content:");
        System.out.println(text);
      }

      s3.close();

    } finally {
      // ----------------------------------------------------------------
      // 8) Clean shutdown
      // ----------------------------------------------------------------
      s3Proxy.stop();
      blobStoreContext.close();
      System.out.println("S3Proxy stopped.");
    }
  }

  private App() {
    // no instances
  }
}
