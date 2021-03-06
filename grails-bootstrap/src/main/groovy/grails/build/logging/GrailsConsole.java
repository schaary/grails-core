/*
 * Copyright 2011 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package grails.build.logging;

import static org.fusesource.jansi.Ansi.ansi;
import static org.fusesource.jansi.Ansi.Color.DEFAULT;
import static org.fusesource.jansi.Ansi.Color.RED;
import static org.fusesource.jansi.Ansi.Color.YELLOW;
import static org.fusesource.jansi.Ansi.Erase.FORWARD;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Stack;

import jline.ConsoleReader;
import jline.Terminal;
import jline.UnsupportedTerminal;
import jline.WindowsTerminal;

import org.apache.tools.ant.BuildException;
import org.codehaus.groovy.grails.cli.ScriptExitException;
import org.codehaus.groovy.grails.cli.interactive.CandidateListCompletionHandler;
import org.codehaus.groovy.grails.cli.logging.GrailsConsoleErrorPrintStream;
import org.codehaus.groovy.grails.cli.logging.GrailsConsolePrintStream;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.StackTraceUtils;
import org.codehaus.groovy.runtime.typehandling.NumberMath;
import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.Ansi.Color;
import org.fusesource.jansi.AnsiConsole;
import org.springframework.util.StringUtils;

/**
 * Utility class for delivering console output in a nicely formatted way.
 *
 * @author Graeme Rocher
 * @since 2.0
 */
public class GrailsConsole {

    private static GrailsConsole instance;
    public static final String LINE_SEPARATOR = System.getProperty("line.separator");
    public static final String CATEGORY_SEPARATOR = "|";
    public static final String PROMPT = "grails> ";
    public static final String SPACE = " ";
    public static final String ERROR = "Error";
    public static final String WARNING = "Warning";
    public static final String STACKTRACE_MESSAGE = " (Use --stacktrace to see the full trace)";
    private StringBuilder maxIndicatorString;
    private int cursorMove;

    /**
     * Whether to enable verbose mode
     */
    private boolean verbose;

    /**
     * Whether to show stack traces
     */
    private boolean stacktrace = Boolean.getBoolean("grails.show.stacktrace");

    private boolean progressIndicatorActive = false;

    /**
     * The progress indicator to use
     */
    String indicator = ".";
    /**
     * The last message that was printed
     */
    String lastMessage = "";

    Ansi lastStatus = null;
    /**
     * The reader to read info from the console
     */
    ConsoleReader reader;

    Terminal terminal;

    PrintStream out;

    /**
     * The category of the current output
     */
    @SuppressWarnings("serial")
    Stack<String> category = new Stack<String>() {
        @Override
        public String toString() {
            if (size() == 1) return peek() + CATEGORY_SEPARATOR;
            return DefaultGroovyMethods.join(this, CATEGORY_SEPARATOR) + CATEGORY_SEPARATOR;
        }
    };

    /**
     * Whether ANSI should be enabled for output
     */
    private boolean ansiEnabled = true;

    /**
     * Whether user input is currently active
     */
    private boolean userInputActive;

    protected GrailsConsole() throws IOException {
        cursorMove = 1;
        out = new PrintStream(AnsiConsole.wrapOutputStream(System.out));

        System.setOut(new GrailsConsolePrintStream(out));
        System.setErr(new GrailsConsoleErrorPrintStream(new PrintStream(AnsiConsole.wrapOutputStream(System.err))));

        if (isWindows()) {
           terminal = new WindowsTerminal() {
               @Override
               public boolean isANSISupported() {
                   return true;
               }
            };
            try {
                terminal.initializeTerminal();
                terminal.enableEcho();
            } catch (Exception e) {
                terminal = new UnsupportedTerminal();
            }
        }
        else {
            terminal = Terminal.setupTerminal();
        }

        reader = new ConsoleReader();
        reader.setBellEnabled(false);
        reader.setCompletionHandler(new CandidateListCompletionHandler());
        // bit of a WTF this, but see no other way to allow a customization indicator
        maxIndicatorString = new StringBuilder(indicator).append(indicator).append(indicator).append(indicator).append(indicator);

        out.println();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().indexOf("windows") != -1;
    }

    public static synchronized GrailsConsole getInstance() {
        if (instance == null) {
            try {
                instance = new GrailsConsole();
            } catch (IOException e) {
                throw new RuntimeException("Cannot create grails console: " + e.getMessage(), e);
            }
        }

        if (!(System.out instanceof GrailsConsolePrintStream)) {
            System.setOut(new GrailsConsolePrintStream(instance.out));
        }
        return instance;
    }

    public void setAnsiEnabled(boolean ansiEnabled) {
        this.ansiEnabled = ansiEnabled;
    }

    /**
     * @param verbose Sets whether verbose output should be used
     */
    public void setVerbose(boolean verbose) {
        if (verbose) {
            // enable big traces in verbose mode
            System.setProperty("grails.full.stacktrace", "true");
        }
        this.verbose = verbose;
    }

    /**
     * @param stacktrace Sets whether to show stack traces on errors
     */
    public void setStacktrace(boolean stacktrace) {
        this.stacktrace = stacktrace;
    }

    /**
     * @return Whether verbose output is being used
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * @return The input stream being read from
     */
    public InputStream getInput() {
        return reader.getInput();
    }

    /**
     * @return The last message logged
     */
    public String getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(String lastMessage) {
        this.lastMessage = lastMessage;
    }

    public ConsoleReader getReader() {
        return reader;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public PrintStream getOut() {
        return out;
    }

    public Stack<String> getCategory() {
        return category;
    }

    /**
     * Indicates progresss with the default progress indicator
     */
    public void indicateProgress() {
        progressIndicatorActive = true;
        if (isAnsiEnabled()) {
            if (StringUtils.hasText(lastMessage)) {
                if (!lastMessage.contains(maxIndicatorString)) {
                    updateStatus(lastMessage + indicator);
                }
            }
        }
        else {
            out.print(indicator);
        }
    }

    /**
     * Indicate progress for a number and total
     *
     * @param number The current number
     * @param total  The total number
     */
    public void indicateProgress(int number, int total) {
        progressIndicatorActive = true;
        String currMsg = lastMessage;
        try {
            updateStatus(currMsg + ' '+ number + " of " + total);
        } finally {
            lastMessage = currMsg;
        }
    }

    /**
     * Indicates progress as a precentage for the given number and total
     *
     * @param number The number
     * @param total  The total
     */
    public void indicateProgressPercentage(long number, long total) {
        progressIndicatorActive = true;
        String currMsg = lastMessage;
        try {
            int percentage = Math.round(NumberMath.multiply(NumberMath.divide(number, total), 100).floatValue());

            if (!isAnsiEnabled()) {
                out.print("..");
                out.print(percentage + '%');
            }
            else {
                updateStatus(currMsg + ' ' + percentage + '%');
            }
        } finally {
            lastMessage = currMsg;
        }
    }

    /**
     * Indicates progress by number
     *
     * @param number The number
     */
    public void indicateProgress(int number) {
        progressIndicatorActive = true;
        String currMsg = lastMessage;
        try {
            if (isAnsiEnabled()) {
                updateStatus(currMsg + ' ' + number);
            }
            else {
                out.print("..");
                out.print(number);
            }
        } finally {
            lastMessage = currMsg;
        }
    }

    /**
     * Updates the current state message
     *
     * @param msg The message
     */
    public void updateStatus(String msg) {
        outputMessage(msg, 1);
    }

    private void outputMessage(String msg, int replaceCount) {
        if (msg == null || msg.trim().length() == 0) return;
        try {
            if (isAnsiEnabled()) {

                lastStatus = outputCategory(erasePreviousLine(CATEGORY_SEPARATOR), CATEGORY_SEPARATOR)
                        .fg(Color.DEFAULT).a(msg).reset();
                out.println(lastStatus);
                if (userInputActive) {
                    out.print(ansi().cursorRight(PROMPT.length()).reset());
                }

                cursorMove = replaceCount;
            } else {
                if (lastMessage != null && lastMessage.equals(msg)) return;

                if (progressIndicatorActive) {
                    out.println();
                }

                out.print(CATEGORY_SEPARATOR);
                out.println(msg);
            }
            lastMessage = msg;
        } finally {
            postPrintMessage();
        }
    }

    private void postPrintMessage() {
        progressIndicatorActive = false;
    }

    /**
     * Keeps doesn't replace the status message
     *
     * @param msg The message
     */
    public void addStatus(String msg) {
        outputMessage(msg, 0);
        lastMessage="";
    }

    /**
     * Prints an error message
     *
     * @param msg The error message
     */
    public void error(String msg) {
        error(ERROR, msg);
    }

    /**
     * Prints an error message
     *
     * @param msg The error message
     */
    public void warning(String msg) {
        error(WARNING, msg);
    }

    /**
     * Prints a warn message
     *
     * @param msg The message
     */
    public void warn(String msg) {
        warning(msg);
    }

    private void logSimpleError(String msg) {
        if (progressIndicatorActive) {
            out.println();
        }
        out.println(CATEGORY_SEPARATOR);
        out.println(msg);
    }

    public boolean isAnsiEnabled() {
        return Ansi.isEnabled() && terminal.isANSISupported() && ansiEnabled;
    }

    /**
     * Use to log an error
     *
     * @param msg The message
     * @param error The error
     */
    public void error(String msg, Throwable error) {
        try {
            if ((verbose||stacktrace) && error != null) {
                printStackTrace(msg, error);
                error(ERROR, msg);
            }
            else {
                error(ERROR, msg + STACKTRACE_MESSAGE);
            }
        } finally {
            postPrintMessage();
        }
    }

    /**
     * Use to log an error
     *
     * @param error The error
     */
    public void error(Throwable error) {
        printStackTrace(null, error);
    }

    private void printStackTrace(String message, Throwable error) {
        if (error instanceof ScriptExitException) {
            return; // don't bother with exit exceptions
        }

        if ((error instanceof BuildException) && error.getCause() != null) {
            error = error.getCause();
        }
        if (!isVerbose()) {
            StackTraceUtils.deepSanitize(error);
        }
        StringWriter sw = new StringWriter();
        PrintWriter ps = new PrintWriter(sw);
        if (message != null) {
            ps.println(message);
        }
        else {
            ps.println(error.getMessage());
        }
        error.printStackTrace(ps);
        error(sw.toString());
    }

    /**
     * Logs a message below the current status message
     *
     * @param msg The message to log
     */
    public void log(String msg) {
        try {
            if (msg.endsWith(LINE_SEPARATOR)) {
                out.print(msg);
            }
            else {
                out.println(msg);
            }
            cursorMove = 0;
        } finally {
            postPrintMessage();
        }
    }

    /**
     * Synonym for #log
     *
     * @param msg The message to log
     */
    public void info(String msg) {
        log(msg);
    }

    public void verbose(String msg) {
        try {
            if (verbose) {
                out.println(msg);
                cursorMove = 0;
            }
        } finally {
            postPrintMessage();
        }
    }

    /**
     * Replays the last status message
     */
    public void echoStatus() {
        if (lastStatus != null) {
            updateStatus(lastStatus.toString());
        }
    }

    /**
     * Replacement for AntBuilder.input() to eliminate dependency of
     * GrailsScriptRunner on the Ant libraries. Prints a message and
     * returns whatever the user enters (once they press &lt;return&gt;).
     * @param msg The message/question to display.
     * @return The line of text entered by the user. May be a blank
     * string.
     */
    public String userInput(String msg) {
        // Add a space to the end of the message if there isn't one already.
        if (!msg.endsWith(" ") && !msg.endsWith("\t")) {
            msg += ' ';
        }

        lastMessage = "";
        msg = isAnsiEnabled() ? outputCategory(ansi(), ">").fg(DEFAULT).a(msg).toString() : msg;
        try {
            return showPrompt(msg);
        } finally {
            cursorMove = 0;
        }
    }

    /**
     * Shows the prompt to request user input
     * @param prompt The prompt to use
     * @return The user input prompt
     */
    private String showPrompt(String prompt) {
        try {
            cursorMove = 0;
            userInputActive = true;
            try {
                return reader.readLine(prompt);
            } finally {
                userInputActive = false;
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading input: " + e.getMessage());
        }
    }
    /**
     * Shows the prompt to request user input
     * @return The user input prompt
     */
    public String showPrompt() {
        String prompt = isAnsiEnabled() ? ansiPrompt(PROMPT).toString() : PROMPT;
        return showPrompt(prompt);
    }

    private Ansi ansiPrompt(String prompt) {
        return ansi()
                .a(Ansi.Attribute.INTENSITY_BOLD)
                .fg(YELLOW)
                .a(prompt)
                .a(Ansi.Attribute.INTENSITY_BOLD_OFF)
                .fg(DEFAULT);
    }

    /**
     * Replacement for AntBuilder.input() to eliminate dependency of
     * GrailsScriptRunner on the Ant libraries. Prints a message and
     * list of valid responses, then returns whatever the user enters
     * (once they press &lt;return&gt;). If the user enters something
     * that is not in the array of valid responses, the message is
     * displayed again and the method waits for more input. It will
     * display the message a maximum of three times before it gives up
     * and returns <code>null</code>.
     * @param message The message/question to display.
     * @param validResponses An array of responses that the user is
     * allowed to enter. Displayed after the message.
     * @return The line of text entered by the user, or <code>null</code>
     * if the user never entered a valid string.
     */
    public String userInput(String message, String[] validResponses) {
        if (validResponses == null) {
            return userInput(message);
        }

        String question = createQuestion(message, validResponses);
        String response = userInput(question);
        for (String validResponse : validResponses) {
            if (response != null && response.equalsIgnoreCase(validResponse)) {
                return response;
            }
        }
        cursorMove = 0;
        return userInput("Invalid input. Must be one of ", validResponses);
    }

    private String createQuestion(String message, String[] validResponses) {
        return message + "[" + DefaultGroovyMethods.join(validResponses, ",") + "] ";
    }

    private Ansi outputCategory(Ansi ansi, String categoryName) {
        return ansi
                .a(Ansi.Attribute.INTENSITY_BOLD)
                .fg(YELLOW)
                .a(categoryName)
                .a(SPACE)
                .a(Ansi.Attribute.INTENSITY_BOLD_OFF);
    }

    private Ansi outputErrorLabel(Ansi ansi, String label) {
        return ansi
                .a(Ansi.Attribute.INTENSITY_BOLD)
                .fg(RED)
                .a(CATEGORY_SEPARATOR)
                .a(SPACE)
                .a(label)
                .a(" ")
                .a(Ansi.Attribute.INTENSITY_BOLD_OFF)
                .fg(Color.DEFAULT);
    }

    private Ansi erasePreviousLine(String categoryName) {
        if (cursorMove > 0) {
            int moveLeftLength = categoryName.length() + lastMessage.length();
            if (userInputActive) {
                moveLeftLength += PROMPT.length();
            }
            return ansi()
                    .cursorUp(cursorMove)
                    .cursorLeft(moveLeftLength)
                    .eraseLine(FORWARD);

        }
        return ansi();
    }

    public void error(String label, String message) {
        if (message != null) {
            cursorMove = 0;
            try {
                if (isAnsiEnabled()) {
                    Ansi ansi = outputErrorLabel(ansi(), label).a(message);
                    if (message.endsWith(LINE_SEPARATOR)) {
                        out.print(ansi);
                    }
                    else {
                        out.println(ansi);
                    }
                }
                else {
                    out.print(label);
                    out.print(" ");
                    logSimpleError(message);
                }
            } finally {
                postPrintMessage();
            }
        }
    }
}
