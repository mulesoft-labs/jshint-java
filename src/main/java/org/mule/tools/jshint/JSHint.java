/**
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.tools.jshint;

import org.apache.commons.io.IOUtils;
import org.mozilla.javascript.*;
import org.mozilla.javascript.tools.shell.Global;
import org.mule.tools.rhinodo.api.ConsoleFactory;
import org.mule.tools.rhinodo.api.NodeModule;
import org.mule.tools.rhinodo.api.Runnable;
import org.mule.tools.rhinodo.impl.JavascriptRunner;
import org.mule.tools.rhinodo.impl.NodeModuleFactoryImpl;
import org.mule.tools.rhinodo.impl.NodeModuleImpl;
import org.mule.tools.rhinodo.rhino.RhinoHelper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class JSHint implements Runnable {
    private NodeModuleImpl jshintModule;
    private JavascriptRunner javascriptRunner;
    private InputStream inputStream;
    private String fileName;
    private NativeArray errors;
    private Map config;
    private RhinoHelper rhinoHelper;

    public JSHint(ConsoleFactory consoleFactory, String destDir) {
        this.rhinoHelper = new RhinoHelper();

        jshintModule = NodeModuleImpl.fromJar(this.getClass(), "META-INF/jshint", destDir);

        List<? extends NodeModule> nodeModuleList = Arrays.asList(
                NodeModuleImpl.fromJar(this.getClass(), "META-INF/cli", destDir),
                NodeModuleImpl.fromJar(this.getClass(), "META-INF/glob", destDir),
                NodeModuleImpl.fromJar(this.getClass(), "META-INF/jshint", destDir),
                NodeModuleImpl.fromJar(this.getClass(), "META-INF/lru-cache", destDir),
                NodeModuleImpl.fromJar(this.getClass(), "META-INF/minimatch", destDir),
                jshintModule);

        javascriptRunner = JavascriptRunner.withConsoleFactory(consoleFactory,new NodeModuleFactoryImpl(nodeModuleList), this, destDir);
    }

    public boolean check(String fileName, InputStream inputStream, Map config) {
        this.inputStream = inputStream;
        this.fileName = fileName;
        this.config = config;
        javascriptRunner.run();
        return errors == null;
    }

    @Override
    public void executeJavascript(Context ctx, Global global) {
        global.put("__dirname", global, Context.toString(jshintModule.getPath()));

        Function require = (Function)global.get("require", global);
        Object jshintRequire = require.call(ctx,global,global,new String [] {"jshint"});
        NativeObject jshintContainer = (NativeObject) Context.jsToJava(jshintRequire, NativeObject.class);
        NativeFunction jshint = (NativeFunction) Context.jsToJava(jshintContainer.get("JSHINT",global), Function.class);

        String s;
        try {
            s = IOUtils.toString(inputStream, "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Object[] args = config != null ? new Object[]{s,rhinoHelper.mapToNativeObject(config)} : new Object[]{s};
        jshint.call(ctx, global, global, args);

        NativeFunction data = (NativeFunction) jshint.get("data");
        NativeObject dataFunctionResult = (NativeObject) data.call(ctx, global, jshint, new Object[]{});
        errors = (NativeArray) dataFunctionResult.get("errors");

        Scriptable console = javascriptRunner.getConsole();
        Function log = (Function) console.get("error", global);
        if (errors!= null && errors.size() > 0) {
            log.call(ctx,global,console,new Object[] {
                    String.format("The following errors were found in file [%s]: ", fileName)});
            for (Object errorAsObject : errors) {
                NativeObject error = (NativeObject) errorAsObject;
                String message = String.format("Line: %d col: %d, %s", ((Double) error.get("line")).longValue(),
                        ((Double) error.get("character")).longValue(), error.get("reason"));

                log.call(ctx, global, console, new Object[]{message});
            }
        }
    }

}
