-- id: hello
-- version: 1.0
-- api: caulis:0.1
-- description: A key, an event and a timer. Save this file and it runs anew.
--
-- A Lua plugin lives in a sandbox: no files, no programs (unless you allow them in your
-- configuration: plugins.allow), no Java. All it does comes through the table `plugin`.

local greeting = plugin.settings.greeting or "Hello"

plugin.bind("Super+F9", function()
  plugin.notify(greeting .. ": " .. #plugin.windows() .. " windows")
end, "says how many windows are open")

plugin.on("window-opened", function(w)
  plugin.log("opened " .. w.appId)
end)

-- A command for other programs: my-compositor --plugin cmd hello-count
plugin.command("hello-count", function()
  return tostring(#plugin.windows())
end)

-- Two windows side by side, each half: chosen with the action `plugin layout halves`.
plugin.layout("halves", function(windows, area)
  local out = {}
  local width = math.floor(area.width / math.max(#windows, 1))
  for i = 1, #windows do
    out[i] = { x = area.x + (i - 1) * width, y = area.y, width = width, height = area.height }
  end
  return out
end)
