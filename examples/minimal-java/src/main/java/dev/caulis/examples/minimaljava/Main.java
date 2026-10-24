package dev.caulis.examples.minimaljava;

import dev.caulis.core.layout.Dwindle;
import dev.caulis.core.layout.Gaps;
import dev.caulis.wm.Caulis;

/** A complete Wayland compositor in Java: the same as examples/minimal. */
public final class Main {
    public static void main(String[] args) {
        Caulis.create()
                .layout(new Dwindle(new Gaps(8, 12)))
                .keyboard("us,ru", "grp:win_space_toggle")
                .bind("Super+Return", c -> c.launch("foot"))
                .bind("Super+D", c -> c.launch("fuzzel"))
                .onStart(c -> c.launch("foot"))
                .run();
    }
}
