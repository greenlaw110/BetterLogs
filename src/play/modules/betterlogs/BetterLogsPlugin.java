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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;

public class BetterLogsPlugin extends PlayPlugin {
    final static Pattern PREFIX_PATTERN = Pattern
            .compile("%file|%line|%thread|%class|%method|%relativeFile|%simpleClass|%package|%signature");
    final static Pattern TRAILING_SPACES_PATTERN = Pattern
            .compile("(\\d+)(t|ws)?");

    static String stringFormatPrefix;
    static ArrayList<String> argsPrefix;
    static boolean disabled = false;

    private static Enhancer e_ = new BetterLogsEnhancer();
    static boolean traceEnabled = false;
    static String traceLevel = "TRACE";
    static String traceMethod = "trace";

    @Override
    public void enhance(ApplicationClass applicationClass) throws Exception {
        if (!disabled && configured_)
            e_.enhanceThisClass(applicationClass);
    }

    private static boolean configured_ = false;
    @Override
    public void onConfigurationRead() {
        if (configured_) return;
        disabled = "true".equals(Play.configuration
                .getProperty("betterlogs.disabled"));
        if (disabled)
            Logger.warn("BetterLogs is disabled. The classes are no more enhanced. If you enable it again, don't forget to clean your app before to force Play to enhance all the classes.");
        ArrayList<String> newArgsPrefix = new ArrayList<String>();
        String prefix = Play.configuration.getProperty("betterlogs.prefix",
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
                "betterlogs.prefix.trailingSpaces", "1ws");
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
        traceLevel = Play.configuration.getProperty("betterlogs.trace.level",
                "TRACE");
        java.util.logging.Level trace_ = toJuliLevel(traceLevel);
        String logLevel = Play.configuration.getProperty("application.log",
                "INFO");
        java.util.logging.Level log_ = toJuliLevel(logLevel);
        traceEnabled = log_.intValue() < trace_.intValue();
        traceMethod = toLogMethod(traceLevel);
        
        configured_ = true;
    }

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
        if (level.equals("ERROR") || level.equals("FATAL")) {
            return java.util.logging.Level.SEVERE;
        }
        if (level.equals("WARN")) {
            return java.util.logging.Level.WARNING;
        }
        if (level.equals("DEBUG")) {
            return java.util.logging.Level.FINE;
        }
        if (level.equals("TRACE")) {
            return java.util.logging.Level.FINEST;
        }
        if (level.equals("ALL")) {
            return java.util.logging.Level.ALL;
        }
        if (level.equals("OFF")) {
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
}
