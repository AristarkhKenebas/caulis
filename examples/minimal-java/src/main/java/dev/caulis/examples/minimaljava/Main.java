package dev.caulis.examples.minimaljava;

import dev.caulis.tiling.Gaps;
import dev.caulis.tiling.Layouts;
import dev.caulis.wm.Caulis;

/** A complete Wayland compositor in Java: the same as examples/minimal. */
public final class Main {
    public static void main(String[] args) {
        Caulis.create()
                .layout(Layouts.Dwindle(new Gaps(8, 12)))
                .keyboard("us,ru", "grp:win_space_toggle")
                .bind("Super+Return", c -> c.launch("foot"))
                .bind("Super+D", c -> c.launch("fuzzel"))
                .onStart(c -> c.launch("foot"))
                .run();
    }
}
