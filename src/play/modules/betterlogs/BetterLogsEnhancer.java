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
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.modules.betterlogs.BetterLogsPlugin.TraceMode;

public class BetterLogsEnhancer extends Enhancer {

    private static boolean traceEnhance_(CtClass ctClass) {
        return BetterLogsPlugin.traceEnabled
                && !ctClass.hasAnnotation(NoTrace.class);
    }

    private static boolean traceEnhance_(CtBehavior ctBehavior) {
        return !ctBehavior.isEmpty()
                && ((BetterLogsPlugin.traceMode == TraceMode.NOTRACE) ? ctBehavior
                        .hasAnnotation(Trace.class) : !ctBehavior
                        .hasAnnotation(NoTrace.class));
    }

    private static void enhance_(CtClass cls, CtBehavior ctb,
            String[] classTraceThemes, String traceMethod) throws Exception {
        if (!traceEnhance_(ctb))
            return;
        // prefix
        CtClass[] types = ctb.getParameterTypes();
        int len = types.length;
        StringBuilder sb;
        if (len == 0) {
            sb = new StringBuilder("Class[] types = new Class[0];");
        } else {
            sb = new StringBuilder("Class[] types = {");
            for (int i = 0; i < len; ++i) {
                if (i > 0)
                    sb.append(", ");
                sb.append(types[i].getName()).append(".class");
            }
            sb.append("};");
        }
        sb.append("java.lang.reflect.Method m = ")
                .append(cls.getName())
                .append(".class.getDeclaredMethod(\"")
                .append(ctb.getName())
                .append("\", types);java.lang.annotation.Annotation a = m.getAnnotation(play.modules.betterlogs.Trace.class);"
                        + "String[] sa = new String[]{\"\"};if (null != a) sa = ((play.modules.betterlogs.Trace)a).value(); "
                        + "if (play.modules.betterlogs.BetterLogsPlugin.traceThemesMatch(sa) || ((sa.length == 1) && \"\".equals(sa[0]))){ play.Logger.")
                .append(traceMethod);
        // entry
        String code = sb.toString() + "(\"%s ...\", sa); }";
        Logger.trace("betterlogs::trace: entry/exit code: %s", code);
        ctb.insertBefore(String.format(code, "enter"));
        // exit
        ctb.insertAfter(String.format(code, "exit"));
    }

    @Override
    public void enhanceThisClass(final ApplicationClass applicationClass)
            throws Exception {
        Logger.trace("BetterLogsEnhancer.enhanceThisClass: enter");
        final CtClass ctClass = makeClass(applicationClass);
        if (ctClass.getName().matches(".*Plugin.*"))
            return;
        if (ctClass.isInterface())
            return;

        Logger.trace("BettterLogs: enhancing %s...", ctClass.getName());
        // entry/exit trace
        if (traceEnhance_(ctClass)) {
            // class level trace theme
            Object o = ctClass.getAnnotation(Trace.class);
            String[] classTraceThemes = {};
            if (null != o)
                classTraceThemes = ((Trace) o).value();
            String traceMethod = BetterLogsPlugin.traceMethod;
            for (final CtBehavior behavior : ctClass.getDeclaredBehaviors()) {
                enhance_(ctClass, behavior, classTraceThemes, traceMethod);
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
