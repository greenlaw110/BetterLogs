package play.modules.betterlogs;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a class or a method to be enhanced by betterlogs for trace purpose
 * 
 * <p>One can give @Trace one or more theme via value. If themes found then they will
 * be used to match what is set in {@link BetterLogsPlugin#setTraceThemes(String...)},
 * if matched then the entry and exit log will be output. If no trace theme value 
 * found in the annotation then the entry and exit log will be output whatever. 
 * 
 * <p>Method level trace theme override class level trace theme if specified
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Trace {
    String[] value() default {};
}
