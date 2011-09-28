package play.modules.betterlogs;

import java.util.Stack;

public class TimeTracker {
    private static ThreadLocal<Stack<Long>> stack_ = new ThreadLocal<Stack<Long>>(){
        @Override protected Stack<Long> initialValue() {
            return new Stack<Long>();
        }
    };
    public static void enter() {
        stack_.get().push(System.currentTimeMillis());
    }
    
    public static long exit() {
        return System.currentTimeMillis() - stack_.get().pop();
    }

}
