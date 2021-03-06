package com.google.gwt.dev.codeserver;

import collide.plugin.server.AbstractCompileThread;
import collide.plugin.server.IsCompileThread;
import collide.plugin.server.ReflectionChannelTreeLogger;
import collide.plugin.server.gwt.CompilerBusyException;
import com.google.collide.dto.CompileResponse.CompilerState;
import com.google.collide.dto.GwtRecompile;
import com.google.collide.dto.server.DtoServerImpls.CompileResponseImpl;
import com.google.collide.dto.server.DtoServerImpls.GwtRecompileImpl;
import com.google.collide.plugin.shared.CompiledDirectory;
import com.google.collide.server.shared.util.ReflectionChannel;
import com.google.collide.shared.util.DebugUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import xapi.log.X_Log;
import xapi.time.X_Time;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.dev.util.log.PrintWriterTreeLogger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public final class GwtCompilerThread extends AbstractCompileThread<GwtRecompile>
implements IsCompileThread<GwtRecompile> {

  public GwtCompilerThread() {
  }
  public GwtCompilerThread(String module) {
    messageKey = this.module = module;
  }

  private final HashMap<String, CompiledDirectory> modules = new HashMap<>();
  ReflectionChannelTreeLogger logger;
  private boolean started, recompile;

  @Override
  protected TreeLogger logger() {
    return logger == null ? new PrintWriterTreeLogger() : logger;
  }
  RecompileController controller;
  private String module;
  private String messageKey;

  // these are native objects, created using reflection
  @Override
  public void run() {
    while (!Thread.interrupted())
      try {
        // grab our request from originating thread
        String compileRequest = io.receive();
        if (compileRequest == null) {
          working = false;
          // no request means we should just sleep for a while
          try {
            synchronized (GwtCompilerThread.class) {
              GwtCompilerThread.class.wait(20000);
            }
          } catch (InterruptedException e) {// wake up!
            Thread.interrupted();
          }
          continue;
        }
        working = true;
        GwtRecompile request = GwtRecompileImpl.fromJsonString(compileRequest);
        module = request.getModule();
        messageKey = request.getMessageKey() == null ? module : request.getMessageKey();
        // prepare a response to let the user know we are working
        final CompileResponseImpl response = CompileResponseImpl.make();
        response.setModule(messageKey).setStaticName(module);
        response.setCompilerStatus(CompilerState.RUNNING);

        Type logLevel = request.getLogLevel();
        if (logLevel != null)
          logger.setMaxDetail(logLevel);
        logger.setModule(messageKey);

        io.send(response.toJson());

        server.get();
        controller = SuperDevUtil.getOrMakeController(
            logger, request, server.getPort());
        CompiledDirectory dir = controller.recompile();
        modules.put(messageKey, dir);
        // notify user we completed successfully
        response.setCompilerStatus(CompilerState.FINISHED);

        try {
          // also notify our frontend that the compiled output has changed
          // start or update a proxy server to pull source files from this
          // compile.
          synchronized (getClass()) {
            status = response;
            startOrUpdateProxy(dir, controller);
          }
          initialize(server.get(), server.getPort());
        } finally {
          final String status = response.toJson();
          X_Time.runLater(new Runnable() {
            @Override
            public void run() {
              X_Time.trySleep(500, 0);
              io.send(status);
            }
          });
        }


        // This message is routed to WebFE
        io.send("_frontend.symlink_" + dir.toString());

        logger.log(Type.INFO, "Finished gwt compile for "
            + controller.getModuleName());

        // reset interrupted flag so we loop back to the beginning
        Thread.interrupted();

      } catch (Throwable e) {
        System.out.println("Exception caught...");
        logger.log(Type.ERROR, "Error encountered during compile : " + e);

        Throwable cause = e;
        while (cause != null) {
          for (StackTraceElement trace : cause.getStackTrace())
            logger.log(Type.ERROR, trace.toString());
          cause = cause.getCause();
        }
        if (status == null) {
          status = CompileResponseImpl.make();
          status.setModule(messageKey);
          status.setStaticName(module);
        }
        status.setCompilerStatus(CompilerState.FAILED);
        io.send(status.toJson());
        if (isFatal(e))
          try {
            logger.log(Type.INFO, "Destroying thread " + getClass());
            io.destroy();
          } catch (Exception ex) {
            ex.printStackTrace();
          }
        return;
      } finally {
        working = false;
      }
  }

  protected boolean isFatal(Throwable e) {
    return true;
  }

  @Override
  public void setChannel(ClassLoader cl, Object io) {
    ReflectionChannel channel = new ReflectionChannel(cl, io);
    this.io = channel;
    logger = new ReflectionChannelTreeLogger(channel, Type.INFO);
  }

  @Override
  public void setOnDestroy(Object runOnDestroy) {
    assert io != null : "You must call .setChannel() before calling .setOnDestroy()."
        + "  Called from " + DebugUtil.getCaller();
    this.io.setOnDestroy(runOnDestroy);
  }

  @Override
  public void compile(String request) throws CompilerBusyException {
    if (working)
      throw new CompilerBusyException(GwtRecompileImpl.fromJsonString(request).getModule());
    synchronized (GwtCompilerThread.class) {
      working = true;
      try {
        if (!isAlive()) {
          start();
        }
      } catch (Exception e) {
        logger().log(Type.ERROR, "Fatal error trying to start gwt compiler", e);
        try {
          io.destroy();
        } catch (Exception e1) {
          e1.printStackTrace();
        }
      } finally {
        // wake everyone up to check if they have work to do
        getClass().notify();
      }
    }
  }

  @Override
  protected void handleBuffer(NetSocket event, Buffer buffer)
      throws IOException {
    // called when this compile is open for business
    // status.setCompilerStatus(CompileStatus.SERVING);
    // io.send(status.toJson());
    //

    int len = buffer.length();
    String path = buffer.getString(0, len);
    Map<String, String> headers = new HashMap<>();
    for (String line : path.split("\n")) {
      int ind = line.indexOf(' ');
      if (ind > 0)
        headers.put(line.substring(0, ind), line.substring(ind + 1));
    }
    if (path.contains("sourcemaps")) {
      path = path.split("sourcemaps[/]")[1].split("\\s")[0];
    }
    // if requesting gwtSourceMap.json, we must resolve the compiled directory
    String module = path.split("/")[0];
    CompiledDirectory dir = modules.get(module);
    if (path.endsWith("gwtSourceMap.json")) {
      if (dir == null) {
        print(event, "<pre>"
            + "Module " + module + "not yet compiled"
            + "</pre>");
        return;
      }
      File extras = new File(dir.getSourceMapDir());
      if (!extras.exists()) {
        throw new RuntimeException("Can't find symbolMaps dir for " + module);
      }
      File[] sourceMapFiles = extras.listFiles(new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.matches(".*_sourceMap.*[.]json");
        }
      });
      if (sourceMapFiles.length == 0) {
        print(event, "<pre>"
            + "No sourcemaps found for " + module
            + "</pre>");
        return;
      }
      File winner = sourceMapFiles[0];
      if (sourceMapFiles.length > 1) {
        // more than one permutation; we need to look up permutation map
        // we have to do browser-sniffing using User-Agent header; ick
        String userAgent = headers.get("User-Agent:").toLowerCase();
        String strongName;
        Map<String, String> permutations = dir.getUserAgentMap();
        // TODO lookup the user agent selection script used and run in ScriptEngine
        if (userAgent.contains("safari")) {
          strongName = permutations.get("safari");
        } else if (userAgent.indexOf("gecko") > 0) {
          strongName = permutations.get("gecko1_8");
        } else {
          strongName = permutations.get("ie9");
          if (strongName == null)
            strongName = permutations.get("ie8");
          if (strongName == null)
            strongName = permutations.get("ie6");
        }
        // see if we've found the permutation we're looking for
        if (strongName != null) {
          for (File candidate : sourceMapFiles) {
            if (candidate.getName().startsWith(strongName)) {
              winner = candidate;
              break;
            }
          }
        }
      } else if (sourceMapFiles.length == 0) {
        // no compiled sourcemaps. Fail.
        throw new RuntimeException("No sourcemap files found in "
            + extras.getCanonicalPath());
      }
      X_Log.trace(getClass(), "Streaming source map file ",winner);
      stream(event, new FileInputStream(winner));
      // event.sendFile(winner.getCanonicalPath());
    } else {
      // assume non-sourcemap request wants a java / resource file.
      URL res = controller.getResourceLoader().getResource(path);
      if (res == null) {
        String resolved = path.replace(module+"/", "");
        if (resolved.startsWith("gen/")) {
          resolved = resolved.replace("gen/", "");
       // Check the generated source filed
          if (dir == null) {
            X_Log.warn(getClass(), "No directory found for ",module," this gwt module may not have finished compiling.");
          } else {
            File genDir = new File(dir.getGenDir());
            if (genDir.exists()) {
              X_Log.debug(getClass(), "Checking gen dir ",genDir," for ",resolved);
              File genSrc = new File(genDir, resolved);
              if (genSrc.exists()) {
                X_Log.debug(getClass(), "Using ",genSrc," for ",path);
                resolved = genSrc.getCanonicalPath();
                res = genSrc.toURI().toURL();
              } else {
                X_Log.debug(getClass(), "No file exists @ ",genSrc," for ",path);
              }
            } else {
              X_Log.warn(getClass(), "gen dir ",genDir," does not exist for ", path);
            }
          }
        } else {
          res = controller.getResourceLoader().getResource(resolved);
        }
        X_Log.trace(getClass(), "Could not find ", path," checking ",resolved," resulted in ", res);
      }
      if (res == null) {
        String error = "Proxy only supports sourcemaps and webserver resources; you sent " + path;
        X_Log.error(getClass(), error);
        print(event, "<pre>" + error + "</pre>");
      } else {
        stream(event, res.openStream());
      }
    }
  }

  private void print(final NetSocket event, String out) throws IOException {
    stream(event, new ByteArrayInputStream(out.getBytes()));
  }

  private void stream(final NetSocket event, InputStream in) throws IOException {
    byte[] buff;
    Buffer b = Buffer.buffer();
    try {
      while (true) {
        buff = new byte[in.available() + 4096];
        int bytesRead = in.read(buff);
        if (bytesRead == -1) {
          return;
        }
        if (buff.length == bytesRead)
          b.appendBytes(buff);
        else
          b.appendBytes(Arrays.copyOf(buff, bytesRead));
      }
    } finally {
      in.close();
      event.write(b);
      event.close();

    }
  }

  @Override
  public boolean isRunning() {
    return working;
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public void kill() {
    if (controller != null) {
      controller.cleanup();
    }
  }

  @Override
  public void doRecompile() {
    recompile = true;
  }

}
