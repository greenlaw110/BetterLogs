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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javassist.runtime.Desc;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.mvc.Http.Request;

public class BetterLogsPlugin extends PlayPlugin {
    
    /**
     * config whether betterlogs enabled or disabled
     */
    public static final String CONF_DISABLED = "betterlogs.disabled";
    /**
     * Config the prefix of log output. Default to "[%relativeFile:%line|%thread] %method() ::"
     */
    public static final String CONF_PREFIX = "betterlogs.prefix";
    /**
     * Config the trailing spaces after prefix. Default to "1ws"
     */
    public static final String CONF_PREFIX_TRAILINGSPACES = "betterlogs.prefix.trailingSpaces";
    /**
     * Config the Log Level of trace. Default to "trace"
     */
    public static final String CONF_TRACE_LEVEL = "betterlogs.trace.level";
    /**
     * config the trace theme, could be multiple themes separated by ","
     */
    public static final String CONF_TRACE_THEME = "betterlogs.trace.theme";
    /**
     * config whether to log action invokation
     */
    public static final String CONF_LOG_ACTION_INVOCATION = "betterlogs.trace.actionInvocation";
    /**
     * config whether to log action invocation time
     */
    public static final String CONF_LOG_ACTION_INVOCATION_TIME = "betterlogs.trace.actionInvocation.time";
    /**
     * config whether set trace themes (configured with {@link CONF_TRACE_THEME}) each time before
     * actions been invoked. Default to false
     */
    public static final String CONF_TRACE_SET_THEME = "betterlogs.trace.setThemes";
    /**
     * Set trace mode. Default to "NOTRACE"
     * - NOTRACE: trace enhancement only to class/method/constructor been annotated with @Trace explicitly
     * - TRACE: trace enhancement to all class/method/constructor with no @NoTrace annotation
     */
    public static final String CONF_TRACE_MODE = "betterlogs.trace.mode";

    final static Pattern PREFIX_PATTERN = Pattern
            .compile("%file|%line|%thread|%class|%method|%relativeFile|%simpleClass|%package|%signature");
    final static Pattern TRAILING_SPACES_PATTERN = Pattern
            .compile("(\\d+)(t|ws)?");

    static String stringFormatPrefix;
    static ArrayList<String> argsPrefix;
    static boolean disabled = false;

    private static Enhancer e_ = new BetterLogsEnhancer();
    static boolean traceEnabled = false;
    static boolean setTraceThemes = false;
    static boolean logActionInvocation = false;
    static boolean logActionInvocationTime = false;
    static String traceLevel = "TRACE";
    static String traceMethod = "trace";
    static TraceMode traceMode = TraceMode.NOTRACE;
    static enum TraceMode {TRACE, NOTRACE};

    @Override
    public void enhance(ApplicationClass applicationClass) throws Exception {
        Logger.trace("betterlogs: prepare to enhance class %s...", applicationClass.name);
        if (!configured_) onConfigurationRead();
        if (!disabled && configured_)
            e_.enhanceThisClass(applicationClass);
    }

    private static boolean configured_ = false;
    @Override
    public void onConfigurationRead() {
        if (configured_) return;
        disabled = "true".equals(Play.configuration
                .getProperty(CONF_DISABLED));
        if (disabled)
            Logger.warn("BetterLogs is disabled. The classes are no more enhanced. If you enable it again, don't forget to clean your app before to force Play to enhance all the classes.");
        else
            Logger.trace("BetterLogs enabled");
        ArrayList<String> newArgsPrefix = new ArrayList<String>();
        String prefix = Play.configuration.getProperty(CONF_PREFIX,
                "[%relativeFile:%line|%thread] %method() ::");
        Matcher matcher = PREFIX_PATTERN.matcher(prefix);
        StringBuffer sb = new StringBuffer();
        if (matcher.find()) {
            int lastEnd = 0;
            do {
                newArgsPrefix.add(matcher.group().substring(1));
                sb.append(
                        prefix.substring(lastEnd, matcher.start()).replace("%",
                                "%%")).append("%s");
                lastEnd = matcher.end();
            } while (matcher.find());
            sb.append(prefix.substring(lastEnd));
        }
        String trailingSpaces = Play.configuration.getProperty(
                CONF_PREFIX_TRAILINGSPACES, "1ws");
        matcher = TRAILING_SPACES_PATTERN.matcher(trailingSpaces);
        if (matcher.matches()) {
            int nb = Integer.parseInt(matcher.group(1));
            char c = "t".equals(matcher.group(2)) ? '\t' : ' ';
            while (nb > 0) {
                sb.append(c);
                nb--;
            }
        }
        argsPrefix = newArgsPrefix;
        stringFormatPrefix = sb.toString();
        
        // enable trace?
        traceLevel = Play.configuration.getProperty(CONF_TRACE_LEVEL,
                "TRACE");
        traceEnabled = logEnabled(traceLevel);
        traceMethod = toLogMethod(traceLevel);
        
        if (traceEnabled) {
            String s = Play.configuration.getProperty(CONF_TRACE_MODE, "NOTRACE");
            try {
                traceMode = TraceMode.valueOf(TraceMode.class, s);
            } catch (Exception e) {
                Logger.warn("invalid tracemode found in config: %s. BetterLogs trace mode set to NOTRACE", traceMode);
            }
            s = Play.configuration.getProperty(CONF_TRACE_SET_THEME, "false");
            setTraceThemes = Boolean.valueOf(s);
        }
        
        logActionInvocationTime = Boolean.parseBoolean(Play.configuration.getProperty(CONF_LOG_ACTION_INVOCATION_TIME, Play.mode.isDev() ? "true":"false"));
        logActionInvocation = Boolean.parseBoolean(Play.configuration.getProperty(CONF_LOG_ACTION_INVOCATION, Play.mode.isDev() ? "true":"false"));
        
        configured_ = true;
    }
    
    //private static final ThreadLocal<Long> perf_ = new ThreadLocal<Long>();
    private static final String KEY = "__BL_COUNTER__";
    @Override
    public void beforeActionInvocation(Method actionMethod) {
        if (logActionInvocation) {
            Logger.info("");
            Logger.info("[BL]>>>>>>> [%s]", Request.current().action);
            if (logActionInvocationTime) Request.current().args.put(KEY, System.currentTimeMillis());
        }
        if (setTraceThemes && traceEnabled){
            String s = Play.configuration.getProperty(CONF_TRACE_THEME, "__DEF__").intern();
            if (!"__DEF__".equals(s)) {
                setTraceThemes(s.split(","));
            }
        }
    }
    
    @Override
    public void afterActionInvocation() {
        if (logActionInvocation) {
            if (logActionInvocationTime) {
				Request request = Request.current();
				if (!request.args.containsKey(KEY)) {
					/*
					 * Rythm Cache4 feature can cause to bypass the beforeActionInvocation logic
					 */
					Logger.info("[BL]<<<<<<< [%s]", Request.current().action);
				} else {
					long start = (Long)Request.current().args.get(KEY), ms = System.currentTimeMillis() - start;
					Logger.info("[BL]<<<<<<< [%s]: %sms", Request.current().action, ms);
				}
            } else {
                Logger.info("[BL]<<<<<<< [%s]", Request.current().action);
            }
            Logger.info("");
        }
        if (!(setTraceThemes && traceEnabled)) return;
    }
    
    /*
     * Compare the log level specified with the application.log level defined in application.conf
     */
    static boolean logEnabled(String level) {
        java.util.logging.Level julLvl = toJuliLevel(level);
        String appLevel = Play.configuration.getProperty("application.log", "INFO").intern();
        java.util.logging.Level julAppLvl = toJuliLevel(appLevel);
        return julAppLvl.intValue() <= julLvl.intValue(); 
    }
    
    @Override
    public void onApplicationStart() {
        Desc.useContextClassLoader = true;
    }
    
//    private static void trace_(String level, String message, Object ... args) {
//        if (!traceEnabled) return;
//        if ("trace".equalsIgnoreCase(level)) {
//            Logger.trace(message, args);
//        } else if ("debug".equalsIgnoreCase(level)) {
//            Logger.debug(message, args);
//        } else if ("info".equalsIgnoreCase(level)) {
//            Logger.info(message, args);
//        } else if ("warn".equalsIgnoreCase(level)) {
//            Logger.warn(message, args);
//        } else if ("error".equalsIgnoreCase(level)) {
//            Logger.error(message, args);
//        } else if ("fatal".equalsIgnoreCase(level)) {
//            Logger.fatal(message, args);
//        } else {
//            Logger.debug(message, args);
//        }
//    }
    
    public static void log(String level, String clazz, String clazzSimpleName,
            String packageName, String method, String signature,
            String fileName, String relativeFileName, int line, Object[] args) {
        Thread thread = Thread.currentThread();
        Throwable throwable = null;
        String pattern = "";
        if (args[0] instanceof Throwable) {
            throwable = (Throwable) args[0];
            pattern = (String) args[1];
        } else {
            pattern = (String) args[0];
        }
        pattern = stringFormatPrefix + pattern;
        Object[] betterLogsArgs = new Object[argsPrefix.size()];
        int i = 0;
        for (String argName : argsPrefix) {
            if ("class".equals(argName))
                betterLogsArgs[i] = clazz;
            if ("simpleClass".equals(argName))
                betterLogsArgs[i] = clazzSimpleName;
            if ("package".equals(argName))
                betterLogsArgs[i] = packageName;
            if ("method".equals(argName))
                betterLogsArgs[i] = method;
            if ("file".equals(argName))
                betterLogsArgs[i] = fileName;
            if ("line".equals(argName))
                betterLogsArgs[i] = line;
            if ("relativeFile".equals(argName))
                betterLogsArgs[i] = relativeFileName;
            if ("signature".equals(argName))
                betterLogsArgs[i] = signature;
            if ("thread".equals(argName))
                betterLogsArgs[i] = thread.getId();
                
            i++;
        }
        if ("trace".equals(level)) {
            Logger.trace(pattern, handleLogArgs(betterLogsArgs, args, 1));
        } else if ("debug".equals(level)) {
            if (throwable != null)
                Logger.debug(throwable, pattern,
                        handleLogArgs(betterLogsArgs, args, 2));
            else
                Logger.debug(pattern, handleLogArgs(betterLogsArgs, args, 1));
        } else if ("info".equals(level)) {
            if (throwable != null)
                Logger.info(throwable, pattern,
                        handleLogArgs(betterLogsArgs, args, 2));
            else
                Logger.info(pattern, handleLogArgs(betterLogsArgs, args, 1));
        } else if ("warn".equals(level)) {
            if (throwable != null)
                Logger.warn(throwable, pattern,
                        handleLogArgs(betterLogsArgs, args, 2));
            else
                Logger.warn(pattern, handleLogArgs(betterLogsArgs, args, 1));
        } else if ("error".equals(level)) {
            if (throwable != null)
                Logger.error(throwable, pattern,
                        handleLogArgs(betterLogsArgs, args, 2));
            else
                Logger.error(pattern, handleLogArgs(betterLogsArgs, args, 1));
        } else if ("fatal".equals(level)) {
            if (throwable != null)
                Logger.fatal(throwable, pattern,
                        handleLogArgs(betterLogsArgs, args, 2));
            else
                Logger.fatal(pattern, handleLogArgs(betterLogsArgs, args, 1));
        }
    }

    private static Object[] handleLogArgs(Object[] injected, Object[] original,
            int skip) {
        Object[] kept = Arrays.copyOfRange(original, skip, original.length - 1);
        if (original[original.length - 1] instanceof Object[]) // flatten
            kept = concat(kept, (Object[]) original[original.length - 1]);
        else
            kept = concat(kept, new Object[] { original[original.length - 1] });
        return concat(injected, kept);
    }

    private static Object[] concat(Object[] o1, Object[] o2) {
        Object[] result = new Object[o1.length + o2.length];
        for (int i = 0; i < o1.length; i++)
            result[i] = o1[i];
        for (int j = 0; j < o2.length; j++)
            result[o1.length + j] = o2[j];
        return result;
    }

    /**
     * Utility method that translayte log4j levels to java.util.logging levels.
     */
    private static java.util.logging.Level toJuliLevel(String level) {
        java.util.logging.Level juliLevel = java.util.logging.Level.INFO;
        if (level.equalsIgnoreCase("ERROR") || level.equalsIgnoreCase("FATAL")) {
            return java.util.logging.Level.SEVERE;
        }
        if (level.equalsIgnoreCase("WARN")) {
            return java.util.logging.Level.WARNING;
        }
        if (level.equalsIgnoreCase("DEBUG")) {
            return java.util.logging.Level.FINE;
        }
        if (level.equalsIgnoreCase("TRACE")) {
            return java.util.logging.Level.FINEST;
        }
        if (level.equalsIgnoreCase("ALL")) {
            return java.util.logging.Level.ALL;
        }
        if (level.equalsIgnoreCase("OFF")) {
            return java.util.logging.Level.OFF;
        }
        return juliLevel;
    }

    /**
     * Utility method that translayte log4j levels to java.util.logging levels.
     */
    private static String toLogMethod(String level) {
        if (level.matches("(FATAL|ERROR|WARN|INFO|DEBUG|TRACE)")) {
            return level.toLowerCase();
        }
        return "trace";
    }
    
    private static Set<String> traceThemes_ = new HashSet<String>();

    private static Set<String> strs_(String... traceThemes) {
        Set<String> set = new HashSet<String>();
        for (String s: traceThemes) {
            set.addAll(Arrays.asList(s.split("[\\s+,;:]+")));
        }
        return set;
    }
    
    /**
     * Set the trace theme at runtime
     * 
     * @param traceThemes
     */
    public static final void setTraceThemes(String ... traceThemes) {
        traceThemes_.clear();
        traceThemes_.addAll(strs_(traceThemes));
    }
    
    /**
     * Called by enhanced code to determine whether annotated trace themes
     * match the the themes set with {@link #setTraceThemes(String...)}
     * 
     * <p>There there is any one theme found in both trace themes, then they
     * are said to be matched and trace should be output
     * 
     * @param traceThemes
     * @return
     */
    public static boolean traceThemesMatch(String ... traceThemes) {
        if (traceThemes.length == 0) return true; // default trace theme
        for (String theme: strs_(traceThemes)) {
            if (traceThemes_.contains(theme)) return true;
        }
        return false;
    }
    
    public static String traceThemesString(String ... traceThemes) {
        if (traceThemes.length == 0) return "_"; //default trace theme
        StringBuilder sb = new StringBuilder();
        for (String s: strs_(traceThemes)) {
            if (traceThemes_.contains(s)) {
                if (sb.length() == 0) sb.append(s);
                else sb.append(",").append(s);
            }
        }
        return sb.toString();
    }
}
