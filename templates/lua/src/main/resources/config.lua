-- Your compositor, in Lua. Save this file and the change is taken on at once. A mistake shows up
-- on the screen with its line, and the settings before stay until it is fixed.
-- The guide: docs/getting-started.md in the Caulis folder, step 27. Every setting: docs/config.schema.json,
-- and docs/lua/caulis.lua gives your editor's Lua server every name.

-- Settings, the same as in config.json.
caulis.settings {
  layout = { name = "dwindle", gaps = { inner = 8, outer = 8 } },   -- or "master-stack", "floating", "columns" ...
  effects = { preset = "modern", cornerRadius = 12 },               -- round corners, a border, soft shadows; "none"
  terminal = "foot",                                                -- what Super+Return opens
  keyboard = { layouts = "us" },                                    -- as for setxkbmap: "us,de", "us,ru" ...
}

-- Keys: an action as text ...
caulis.bind("Super+D", "exec fuzzel")          -- a launcher, any you have installed
caulis.bind("Super+B", "exec firefox")

-- ... or a function: the layout changed by a key.
local tiled = true
caulis.bind("Super+T", function()
  tiled = not tiled
  caulis.run(tiled and "layout dwindle" or "layout floating")
end)

-- Rules: how some windows open.
caulis.rule { program = "pavucontrol", floating = true, center = true }

-- Events: when something happens. At the start: a terminal, and a word of welcome.
caulis.on("start", function()
  caulis.run("terminal")
  caulis.notify("Welcome. Change me: ~/.config/my-compositor/config.lua")
end)
