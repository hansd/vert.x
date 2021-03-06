package org.vertx.java.core.app.cli;

import org.vertx.java.core.Handler;
import org.vertx.java.core.SimpleHandler;
import org.vertx.java.core.app.AppManager;
import org.vertx.java.core.app.AppType;
import org.vertx.java.core.app.Args;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.cluster.EventBus;
import org.vertx.java.core.cluster.spi.ClusterManager;
import org.vertx.java.core.internal.VertxInternal;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.net.NetClient;
import org.vertx.java.core.net.NetSocket;
import org.vertx.java.core.net.ServerID;
import org.vertx.java.core.parsetools.RecordParser;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class VertxMgr {

  private static final Logger log = Logger.getLogger(VertxMgr.class);

  public static void main(String[] args) {
    new VertxMgr(args);
  }

  private VertxMgr(String[] sargs) {
    Args args = new Args(sargs);

    if (sargs.length == 0) {
      displayHelp();
    } else {

      int port = args.getInt("-port");
      port = port == -1 ? SocketDeployer.DEFAULT_PORT: port;

      VertxCommand cmd = null;

      if (sargs[0].equalsIgnoreCase("start")) {
        startServer(args);
      } else if (sargs[0].equalsIgnoreCase("stop")) {
        cmd = new StopCommand();
        System.out.println("Stopped vert.x server");
      } else if (sargs[0].equalsIgnoreCase("deploy")) {
        /*
        Deploy syntax:

        deploy -<java|ruby|groovy|js> -name <name> -main <main> -cp <classpath> -instances <instances>

        type is mandatory
        name is optional, system will generate one if not provided
        main is mandatory
        cp is mandatory
        instances is optional, defaults to number of cores on server
         */

        AppType type = AppType.JAVA;
        String flag = args.map.get("-ruby");
        if (flag != null) {
          type  = AppType.RUBY;
        }
        flag = args.map.get("-js");
        if (flag != null) {
          type = AppType.JS;
        }
        flag = args.map.get("-groovy");
        if (flag != null) {
          type = AppType.GROOVY;
        }

        String name = args.map.get("-name");
        if (name == null) {
          name = "app-" + UUID.randomUUID().toString();
        }

        String main = args.map.get("-main");
        String cp = args.map.get("-cp");

        if (main == null || cp == null) {
          displayDeploySyntax();
          return;
        }

        String sinstances = args.map.get("-instances");
        int instances;
        if (sinstances != null) {
          try {
            instances = Integer.parseInt(sinstances);

            if (instances != -1 && instances < 1) {
              System.err.println("Invalid number of instances");
              displayDeploySyntax();
              return;
            }
          } catch (NumberFormatException e) {
            displayDeploySyntax();
            return;
          }
        } else {
          instances = -1;
        }

        String[] parts;
        if (cp.contains(":")) {
          parts = cp.split(":");
        } else {
          parts = new String[] { cp };
        }
        int index = 0;
        URL[] urls = new URL[parts.length];
        for (String part: parts) {
          File file = new File(part);
          part = file.getAbsolutePath();
          if (!part.endsWith(".jar") && !part.endsWith(".zip") && !part.endsWith("/")) {
            //It's a directory - need to add trailing slash
            part += "/";
          }
          URL url;
          try {
            url = new URL("file://" + part);
          } catch (MalformedURLException e) {
            System.err.println("Invalid directory/jar: " + part);
            return;
          }
          urls[index++] = url;
        }
        cmd = new DeployCommand(type, name, main, urls, instances);
        System.out.println("Deploying application name: " + name + " instances: " + instances);

      } else if (sargs[0].equalsIgnoreCase("undeploy")) {
        String name = args.map.get("-name");
        if (name == null) {
          displayUndeploySyntax();
          return;
        }
        cmd = new UndeployCommand(name);
      } else {
        displayHelp();
      }

      if (cmd != null) {
        String res = sendCommand(port, cmd);
        System.out.println(res);
      }
    }
  }

  private void startServer(Args args) {
    boolean clustered = false;
    if (args.map.get("-cluster") != null) {
      clustered = true;
      int clusterPort = args.getInt("-cluster-port");
      if (clusterPort == -1) {
        clusterPort = 25500;
      }
      String clusterHost = args.map.get("-cluster-host");
      if (clusterHost == null) {
        clusterHost = "0.0.0.0";
      }
      String clusterProviderClass = args.map.get("-cluster-provider");
      if (clusterProviderClass == null) {
        clusterProviderClass = "org.vertx.java.core.cluster.spi.hazelcast.HazelcastClusterManager";
      }
      final Class clusterProvider;
      try {
        clusterProvider= Class.forName(clusterProviderClass);
      } catch (ClassNotFoundException e) {
        System.err.println("Cannot find class " + clusterProviderClass);
        return;
      }
      final ServerID clusterServerID = new ServerID(clusterPort, clusterHost);
      final CountDownLatch latch = new CountDownLatch(1);
      VertxInternal.instance.go(new Runnable() {
        public void run() {
          ClusterManager mgr;
          try {
            mgr = (ClusterManager)clusterProvider.newInstance();
          } catch (Exception e) {
            e.printStackTrace(System.err);
            System.err.println("Failed to instantiate cluster provider");
            return;
          }
          EventBus bus = new EventBus(clusterServerID, mgr) {
          };
          EventBus.initialize(bus);
          latch.countDown();
        }
      });
      try {
        latch.await();
      } catch (InterruptedException ignore) {
      }
    }
    System.out.println("vert.x server started" + (clustered ? " in clustered mode" : ""));
    AppManager mgr = new AppManager(args.getInt("-deploy-port"));
    mgr.start();
  }


  private void displayHelp() {
    System.out.println("vertx command help\n------------------\n");
    displayStartSyntax();
    System.out.println("------------------\n");
    displayStopSyntax();
    System.out.println("------------------\n");
    displayDeploySyntax();
    System.out.println("------------------\n");
    displayUndeploySyntax();
  }

  private void displayStopSyntax() {
    String msg =
    "Stop a vert.x server:\n\n" +
    "vertx stop -port <port>\n" +
    "<port> - the port to connect to the server at. Defaults to 25571";
    System.out.println(msg);
  }

  private void displayStartSyntax() {
    String msg =
    "Start a vert.x server:\n\n" +
    "vertx start -port <port>\n" +
    "<port> - the port the server will listen at. Defaults to 25571";
    System.out.println(msg);
  }

  private void displayDeploySyntax() {
    String msg =
    "Deploy an application:\n\n" +
    "vertx deploy -[java|ruby|groovy|js] -name <name> -main <main> -cp <classpath> -instances <instances> -port <port>\n" +
    "-[java|ruby|groovy|js] depending on the language of the application\n" +
    "<name> - unique name of the application. If this ommitted the server will generate a name\n" +
    "<main> - main class or script for the application\n" +
    "<classpath> - classpath to use\n" +
    "<instances> - number of instances of the application to start. Must be > 0 or -1.\n" +
    "              if -1 then instances will be default to number of cores on the server\n" +
    "<port> - the port to connect to the server at. Defaults to 25571";
    System.out.println(msg);
  }

  private void displayUndeploySyntax() {
    String msg =
    "Undeploy an application:\n\n" +
    "vertx undeploy -name <name> -port <port>\n" +
    "<name> - unique name of the application.\n" +
    "<port> - the port to connect to the server at. Defaults to 25571\n";
    System.out.println(msg);
  }

  private String sendCommand(final int port, final VertxCommand command) {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicReference<String> result = new AtomicReference<>();
    VertxInternal.instance.go(new Runnable() {
      public void run() {
        final NetClient client = new NetClient();
        client.connect(port, "localhost", new Handler<NetSocket>() {
          public void handle(NetSocket socket) {
            if (command.isBlock()) {
              socket.dataHandler(RecordParser.newDelimited("\n", new Handler<Buffer>() {
                public void handle(Buffer buff) {
                  result.set(buff.toString());
                  client.close();
                  latch.countDown();
                }
              }));
              command.write(socket, null);
            } else {
              command.write(socket, new SimpleHandler() {
                public void handle() {
                  client.close();
                  latch.countDown();
                }
              });
            }
          }
        });
      }
    });
    while (true) {
      try {
        if (!latch.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Timed out while sending command");
        }
        break;
      } catch (InterruptedException e) {
        //Ignore
      }
    }

    return result.get();
  }
}
