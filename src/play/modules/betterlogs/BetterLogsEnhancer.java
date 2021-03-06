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
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import play.Logger;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.modules.betterlogs.BetterLogsPlugin.TraceMode;

public class BetterLogsEnhancer extends Enhancer {

    private static boolean hasAnnotationType_(Class<?> clz, ClassPool cp,
            AnnotationsAttribute a1, AnnotationsAttribute a2) {
        Annotation[] anno1, anno2;

        if (a1 == null)
            anno1 = null;
        else
            anno1 = a1.getAnnotations();

        if (a2 == null)
            anno2 = null;
        else
            anno2 = a2.getAnnotations();

        String typeName = clz.getName();
        if (anno1 != null)
            for (int i = 0; i < anno1.length; i++)
                if (anno1[i].getTypeName().equals(typeName))
                    return true;

        if (anno2 != null)
            for (int i = 0; i < anno2.length; i++)
                if (anno2[i].getTypeName().equals(typeName))
                    return true;

        return false;
    }

    private static Object toAnnoType_(Annotation anno, ClassPool cp)
            throws ClassNotFoundException {
        try {
            ClassLoader cl = cp.getClassLoader();
            return anno.toAnnotationType(cl, cp);
        } catch (ClassNotFoundException e) {
            ClassLoader cl2 = cp.getClass().getClassLoader();
            return anno.toAnnotationType(cl2, cp);
        }
    }

    private static Object getAnnotationType_(Class<?> clz, ClassPool cp,
            AnnotationsAttribute a1, AnnotationsAttribute a2)
            throws ClassNotFoundException {
        Annotation[] anno1, anno2;

        if (a1 == null)
            anno1 = null;
        else
            anno1 = a1.getAnnotations();

        if (a2 == null)
            anno2 = null;
        else
            anno2 = a2.getAnnotations();

        String typeName = clz.getName();
        if (anno1 != null)
            for (int i = 0; i < anno1.length; i++)
                if (anno1[i].getTypeName().equals(typeName))
                    return toAnnoType_(anno1[i], cp);

        if (anno2 != null)
            for (int i = 0; i < anno2.length; i++)
                if (anno2[i].getTypeName().equals(typeName))
                    return toAnnoType_(anno2[i], cp);

        return null;
    }

    public static boolean hasAnnotation(CtClass ctClass, Class<?> annType) {
        ClassFile cf = ctClass.getClassFile2();
        AnnotationsAttribute ainfo = (AnnotationsAttribute) cf
                .getAttribute(AnnotationsAttribute.invisibleTag);
        AnnotationsAttribute ainfo2 = (AnnotationsAttribute) cf
                .getAttribute(AnnotationsAttribute.visibleTag);
        return hasAnnotationType_(annType, ctClass.getClassPool(), ainfo,
                ainfo2);
    }

    public static boolean hasAnnotation(CtBehavior ctBehavior,
            Class<?> annType) {
        MethodInfo mi = ctBehavior.getMethodInfo2();
        AnnotationsAttribute ainfo = (AnnotationsAttribute) mi
                .getAttribute(AnnotationsAttribute.invisibleTag);
        AnnotationsAttribute ainfo2 = (AnnotationsAttribute) mi
                .getAttribute(AnnotationsAttribute.visibleTag);
        return hasAnnotationType_(annType, ctBehavior.getDeclaringClass()
                .getClassPool(), ainfo, ainfo2);
    }

    private static boolean traceEnhance_(CtClass ctClass) {
        return BetterLogsPlugin.traceEnabled
                && !hasAnnotation(ctClass, NoTrace.class);
    }

    private static boolean traceEnhance_(CtBehavior ctBehavior) {
        return !ctBehavior.isEmpty()
                && ((BetterLogsPlugin.traceMode == TraceMode.NOTRACE) ? 
                        hasAnnotation(ctBehavior, Trace.class) : !hasAnnotation(ctBehavior, NoTrace.class));
    }

    private static void enhance_(CtClass cls, CtBehavior ctb,
            String[] classTraceThemes, String traceMethod) throws Exception {
        if (!traceEnhance_(ctb))
            return;
        // prefix
        CtClass[] types = ctb.getParameterTypes();
        int len = types.length;
        StringBuilder sb = new StringBuilder("%sClass[] types = ");
        if (len == 0) {
            sb.append("new Class[0];");
        } else {
            sb.append("{");
            for (int i = 0; i < len; ++i) {
                if (i > 0)
                    sb.append(", ");
                sb.append(types[i].getName()).append(".class");
            }
            sb.append("};");
        }
        boolean isConstructor = ctb instanceof CtConstructor;
        sb.append("java.lang.reflect.")
                .append(isConstructor ? "Constructor" : "Method")
                .append(" m = ")
                .append(cls.getName())
                .append(isConstructor ? ".class.getDeclaredConstructor("
                        : ".class.getDeclaredMethod(\"")
                .append(isConstructor ? "" : ctb.getName())
                .append(isConstructor ? "" : "\", ")
                .append("types);java.lang.annotation.Annotation a = m.getAnnotation(play.modules.betterlogs.Trace.class);"
                        + "String[] sa = new String[]{\"\"};if (null != a) sa = ((play.modules.betterlogs.Trace)a).value(); "
                        + "if (play.modules.betterlogs.BetterLogsPlugin.traceThemesMatch(sa) || ((sa.length == 1) && \"\".equals(sa[0]))){ play.Logger.")
                .append(traceMethod)
                .append("(\"[\" + play.modules.betterlogs.BetterLogsPlugin.traceThemesString(sa) + ");
        // entry
        String code = sb.toString() + "\"]%s%s ...\", sa); }";
        Logger.trace("betterlogs::trace: entry/exit code: %s:", code);
        ctb.insertBefore(String.format(code, "play.modules.betterlogs.TimeTracker.enter();", "enter", ""));
        // exit
        ctb.insertAfter(String.format(code, "", "exit", ": \" + play.modules.betterlogs.TimeTracker.exit() + \"ms"), true);
        
    }
    
    public static Object getAnnotation(CtClass ctClass, Class<?> annType) throws ClassNotFoundException {
        ClassFile cf = ctClass.getClassFile2();
        AnnotationsAttribute ainfo = (AnnotationsAttribute)
                cf.getAttribute(AnnotationsAttribute.invisibleTag);  
        AnnotationsAttribute ainfo2 = (AnnotationsAttribute)
                cf.getAttribute(AnnotationsAttribute.visibleTag);  
        return getAnnotationType_(annType, ctClass.getClassPool(), ainfo, ainfo2);
    }

    public static Object getAnnotation(CtBehavior ctBehavior, Class<?> annType) throws ClassNotFoundException {
        MethodInfo mi = ctBehavior.getMethodInfo2();
        AnnotationsAttribute ainfo = (AnnotationsAttribute)
                    mi.getAttribute(AnnotationsAttribute.invisibleTag);  
        AnnotationsAttribute ainfo2 = (AnnotationsAttribute)
                    mi.getAttribute(AnnotationsAttribute.visibleTag);  
        return getAnnotationType_(annType, ctBehavior.getDeclaringClass().getClassPool(), ainfo, ainfo2);
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
            Object o = getAnnotation(ctClass, Trace.class);
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
