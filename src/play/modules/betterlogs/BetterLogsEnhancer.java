/*
 * Copyright 2011 Stephane Godbillon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package play.modules.betterlogs;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.modules.betterlogs.BetterLogsPlugin.TraceMode;

public class BetterLogsEnhancer extends Enhancer {
    
    private boolean traceEnhance_(CtClass ctClass) {
        return BetterLogsPlugin.traceEnabled && !ctClass.hasAnnotation(NoTrace.class);
    }
    
    private boolean traceEnhance_(CtMethod ctMethod) {
        return !ctMethod.isEmpty() && ((BetterLogsPlugin.traceMode == TraceMode.NOTRACE) ? ctMethod.hasAnnotation(Trace.class) : !ctMethod.hasAnnotation(NoTrace.class));
    }
    
    private boolean traceEnhance_(CtConstructor ctConstructor) {
        return ((BetterLogsPlugin.traceMode == TraceMode.NOTRACE) ? ctConstructor.hasAnnotation(Trace.class) : !ctConstructor.hasAnnotation(NoTrace.class));
    }
    
    @Override
    public void enhanceThisClass(final ApplicationClass applicationClass)
            throws Exception {
        Logger.trace("BetterLogsEnhancer.enhanceThisClass: enter");
        final CtClass ctClass = makeClass(applicationClass);
        if (ctClass.getName().matches(".*Plugin.*")) return;
        if (ctClass.isInterface()) return;
         
        Logger.trace("BettterLogs: enhancing %s...", ctClass.getName());
        // entry/exit trace
        if (traceEnhance_(ctClass)) {
            String traceMethod = BetterLogsPlugin.traceMethod;
            for (final CtMethod ctMethod : ctClass.getDeclaredMethods()) {
                if (!traceEnhance_(ctMethod)) continue;
                // entry
                String code = String.format("java.lang.String[] args = null; {play.Logger.%s(\"enter ...\", args);}",
                        traceMethod);
                ctMethod.insertBefore(code);
                // exit
                code = String.format("{java.lang.String[] args = null; play.Logger.%s(\"exit ...\", args);}",
                        traceMethod);
                ctMethod.insertAfter(code);
            }
            for (final CtConstructor ctConstructor: ctClass.getConstructors()) {
                if (!traceEnhance_(ctConstructor)) continue;
                //entry
                String code = String.format("java.lang.String[] args = null; {play.Logger.%s(\"enter ...\", args);}",
                        traceMethod);
                ctConstructor.insertBefore(code);
                // exit
                code = String.format("{java.lang.String[] args = null; play.Logger.%s(\"exit ...\", args);}",
                        traceMethod);
                ctConstructor.insertAfter(code);
            }
            ctClass.defrost();
        }
        for (final CtBehavior behavior : ctClass.getDeclaredBehaviors()) {
            behavior.instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall m) throws CannotCompileException {
                    try {
                        if ("play.Logger".equals(m.getClassName())) {
                            String name = m.getMethodName();
                            // String level, String clazz, String
                            // clazzSimpleName, String packageName, String
                            // method, String signature, String fileName, String
                            // relativeFileName, int line, Object[] args
                            if ("trace".equals(name) || "debug".equals(name)
                                    || "info".equals(name)
                                    || "warn".equals(name)
                                    || "error".equals(name)
                                    || "fatal".equals(name)) {
                                String code = String
                                        .format("{play.modules.betterlogs.BetterLogsPlugin.log(\"%s\", \"%s\", \"%s\", \"%s\", \"%s\", \"%s\", \"%s\", \"%s\", %s, %s);}",
                                                name,
                                                ctClass.getName(), // canonical
                                                                   // name
                                                ctClass.getSimpleName(), // simple
                                                                         // name
                                                ctClass.getPackageName(), // package
                                                behavior.getName(), behavior
                                                        .getSignature(), m
                                                        .getFileName(),
                                                applicationClass.javaFile
                                                        .relativePath(), m
                                                        .getLineNumber(),
                                                "$args" // original args
                                        );
                                m.replace(code);
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }

        applicationClass.enhancedByteCode = ctClass.toBytecode();
        ctClass.defrost();
    }
}
