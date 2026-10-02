package rahu.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** S03 CLI behavior (cli.md exit codes; A21 offline determinism). */
class CliExitCodesTest {

    @Test
    void demoExitsZero() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setOut(new PrintWriter(out, true));
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("demo");
        assertEquals(0, code);
    }

    @Test
    void unknownSubcommandExitsTwo() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new Main());
        cmd.setErr(new PrintWriter(err, true));
        int code = cmd.execute("no-such-command");
        assertEquals(2, code);
    }
}
