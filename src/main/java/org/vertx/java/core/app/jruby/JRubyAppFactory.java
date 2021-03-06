package org.vertx.java.core.app.jruby;

import org.vertx.java.core.app.AppFactory;
import org.vertx.java.core.app.ParentLastURLClassLoader;
import org.vertx.java.core.app.VertxApp;

import java.net.URL;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class JRubyAppFactory implements AppFactory {
  public VertxApp createApp(String main, URL[] urls, ClassLoader parentCL) throws Exception {
    if (System.getProperty("jruby.home") == null) {
      throw new IllegalStateException("In order to deploy Ruby applications you must set JRUBY_HOME to point " +
          "at your JRuby installation");
    }
    ClassLoader cl = new ParentLastURLClassLoader(urls, getClass().getClassLoader());
    VertxApp app = new JRubyApp(main, cl);
    return app;
  }
}
