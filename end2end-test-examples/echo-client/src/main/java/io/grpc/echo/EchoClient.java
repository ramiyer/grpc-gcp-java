package io.grpc.echo;

import static io.opencensus.exporter.trace.stackdriver.StackdriverExporter.createAndRegister;
import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.echo.Echo.EchoResponse;
import io.grpc.echo.Echo.EchoWithResponseSizeRequest;
import io.grpc.echo.GrpcCloudapiGrpc.GrpcCloudapiStub;
import io.grpc.echo.GrpcCloudapiGrpc.GrpcCloudapiBlockingStub;
import io.grpc.stub.StreamObserver;
import io.opencensus.common.Scope;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;

public class EchoClient {
  private static final Logger logger = Logger.getLogger(EchoClient.class.getName());

  private static final String DEFAULT_HOST = "staging-grpc-cfe-benchmarks-with-esf.googleapis.com";
  private static final int PORT = 443;

//  private final ManagedChannel originalChannel;
  private final ManagedChannel[] channels;

  private final GrpcCloudapiBlockingStub blockingStub;
  private final GrpcCloudapiStub[] asyncStubs;

  private int rr;

  public EchoClient(String host, int port, String cookie, boolean corp, int numChannels) {

    channels = new ManagedChannel[numChannels];
    asyncStubs = new GrpcCloudapiStub[numChannels];
    rr = 0;

    for (int i = 0; i < numChannels; i++) {
      channels[i] = ManagedChannelBuilder.forAddress(host, port).build();
      asyncStubs[i] = GrpcCloudapiGrpc.newStub(channels[i]);
    }

    // blocking stub test only needs one channel.
    ClientInterceptor interceptor = new HeaderClientInterceptor(cookie, corp);
    Channel interceptingChannel = ClientInterceptors.intercept(channels[0], interceptor);
    blockingStub = GrpcCloudapiGrpc.newBlockingStub(interceptingChannel);
  }

  public void shutdown() throws InterruptedException {
    for (ManagedChannel channel : channels) {
      channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private GrpcCloudapiStub getNextAsyncStub() {
    GrpcCloudapiStub next = asyncStubs[rr];
    rr = (rr + 1) % asyncStubs.length;
    return next;
  }

  public void asyncEcho(EchoWithResponseSizeRequest request, CountDownLatch latch, List<Long> timeList) {
    GrpcCloudapiStub stub = getNextAsyncStub();
    stub.echoWithResponseSize(request, new StreamObserver<EchoResponse>() {
      long start = System.currentTimeMillis();

      @Override
      public void onNext(EchoResponse value) {
      }

      @Override
      public void onError(Throwable t) {
        latch.countDown();
        Status status = Status.fromThrowable(t);
        logger.log(Level.WARNING, "Encountered an error in echo RPC. Status: " + status);
        t.printStackTrace();
      }

      @Override
      public void onCompleted() {
        long now = System.currentTimeMillis();
        if (timeList != null) {
          timeList.add(now - start);
        }
        latch.countDown();
      }
    });
  }

  public void echo(EchoWithResponseSizeRequest request, Tracer tracer, List<Long> timeList) {
    if (tracer != null) {
      try (Scope scope = tracer.spanBuilder("echo_java").startScopedSpan()) {
        try {
          long start = System.currentTimeMillis();
          blockingStub.echoWithResponseSize(request);
          if (timeList != null) {
            timeList.add(System.currentTimeMillis() - start);
          }
        } catch (StatusRuntimeException e) {
          logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
        }
      }
    } else {
      try {
        long start = System.currentTimeMillis();
        blockingStub.echoWithResponseSize(request);
        if (timeList != null) {
          timeList.add(System.currentTimeMillis() - start);
        }
      } catch (StatusRuntimeException e) {
        logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      }
    }
  }

  private static String generateString(int numBytes) {
    StringBuilder sb = new StringBuilder(numBytes);
    for (int i = 0; i < numBytes; i++) {
      sb.append('x');
    }
    return sb.toString();
  }

  /** return a global tracer */
  private static Tracer getTracer() {
    try {
      createAndRegister();
    } catch (IOException e) {
      return null;
    }
    return Tracing.getTracer();
  }

  private static void printResult(int numRpcs, int numChannels, int payload, List<Long> timeList) {
    Collections.sort(timeList);
    System.out.println(
        String.format(
            "[Number of RPCs: %d, "
                + "Number of channels: %d, "
                + "Payload Size: %dKB]\n"
                + "\t\tAvg"
                + "\tMin"
                + "\tp50"
                + "\tp90"
                + "\tp99"
                + "\tMax\n"
                + "  Time(ms)\t%d\t%d\t%d\t%d\t%d\t%d",
            numRpcs,
            numChannels,
            payload,
            timeList.stream().mapToLong(Long::longValue).sum() / timeList.size(),
            timeList.get(0),
            timeList.get((int) (timeList.size() * 0.5)),
            timeList.get((int) (timeList.size() * 0.9)),
            timeList.get((int) (timeList.size() * 0.99)),
            timeList.get(timeList.size() - 1)));
  }

  public static void main(String[] args) throws Exception {
    ArgumentParser parser =
        ArgumentParsers.newFor("Echo client test")
            .build()
            .defaultHelp(true)
            .description("Echo client java binary");

    parser.addArgument("--payloadKB").type(Integer.class).setDefault(1);
    parser.addArgument("--numRpcs").type(Integer.class).setDefault(5);
    parser.addArgument("--tracer").type(Boolean.class).setDefault(false);
    parser.addArgument("--cookie").type(String.class).setDefault("");
    parser.addArgument("--wait").type(Integer.class).setDefault(0);
    parser.addArgument("--corp").type(Boolean.class).setDefault(false);
    parser.addArgument("--warmup").type(Integer.class).setDefault(5);
    parser.addArgument("--host").type(String.class).setDefault(DEFAULT_HOST);
    parser.addArgument("--async").type(Boolean.class).setDefault(false);
    parser.addArgument("--numChannels").type(Integer.class).setDefault(1);
    parser.addArgument("--asyncTimeoutInSecs").type(Integer.class).setDefault(5);

    Namespace ns = parser.parseArgs(args);

    // Read args
    int payloadKB = ns.getInt("payloadKB");
    int numRpcs = ns.getInt("numRpcs");
    boolean enableTracer = ns.getBoolean("tracer");
    String cookie = ns.getString("cookie");
    int wait = ns.getInt("wait");
    boolean corp = ns.getBoolean("corp");
    int warmup = ns.getInt("warmup");
    String host = ns.getString("host");
    boolean async = ns.getBoolean("async");
    int numChannels = ns.getInt("numChannels");
    int asyncTimeoutInSecs = ns.getInt("asyncTimeoutInSecs");

    String message = generateString(payloadKB * 1024);

    // Prepare tracer
    Tracer tracer = null;
    if (enableTracer) {
      tracer = getTracer();
      if (tracer == null) {
        logger.log(Level.WARNING, "Cannot get tracer");
      }
      TraceConfig globalTraceConfig = Tracing.getTraceConfig();
      globalTraceConfig.updateActiveTraceParams(
          globalTraceConfig.getActiveTraceParams().toBuilder()
              .setSampler(Samplers.alwaysSample())
              .build());
    }

    // Starting test
    EchoWithResponseSizeRequest request =
            EchoWithResponseSizeRequest.newBuilder().setEchoMsg(message).setResponseSize(10).build();

    EchoClient client = new EchoClient(host, PORT, cookie, corp, numChannels);
    if (!async) {
      try {
        // Warm up calls
        for (int i = 0; i < warmup; i++) {
          client.echo(request, null, null);
        }

        long start = System.currentTimeMillis();
        List<Long> timeList = new ArrayList<>(numRpcs);
        for (int i = 0; i < numRpcs; i++) {
          client.echo(request, tracer, timeList);
          if (wait > 0) {
            Thread.sleep(wait * 1000);
          }
        }
        long duration = System.currentTimeMillis() - start;
        System.out.println("total time: " + duration + "ms");
        printResult(numRpcs, 1, payloadKB, timeList);
      } finally {
        client.shutdown();
      }
    } else {
      try {
        GrpcCloudapiStub[] stubs = new GrpcCloudapiStub[numChannels];
        for (int i = 0; i < numChannels; i++) {
          ManagedChannel channel = ManagedChannelBuilder.forAddress(host, 443).build();
          stubs[i] = GrpcCloudapiGrpc.newStub(channel);
        }

        // Warm up calls
        CountDownLatch latch = new CountDownLatch(warmup);
        for (int i = 0; i < warmup; i++) {
          client.asyncEcho(request, latch, null);
        }
        latch.await();

        long start = System.currentTimeMillis();
        latch = new CountDownLatch(numRpcs);
        List<Long> timeList = new ArrayList<>();
        for (int i = 0; i < numRpcs; i++) {
          client.asyncEcho(request, latch, timeList);
        }
        latch.await(asyncTimeoutInSecs, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        System.out.println("total time: " + duration + "ms");

        printResult(numRpcs, numChannels, payloadKB, timeList);
      } finally {
        client.shutdown();
      }
    }
  }
}
