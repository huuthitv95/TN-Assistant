import { readFileSync } from "node:fs";
import { it, expect } from "vitest";
it("runtime canonical palette matches documented durable tokens", () => {
  const design = readFileSync("DESIGN.md", "utf8");
  const css = readFileSync("src/style.css", "utf8");
  for (const [name, value] of [
    ["navy", "#021f4e"],
    ["primary", "#0866c6"],
    ["cyan", "#49d6ef"],
    ["danger", "#b42337"],
    ["text", "#152f50"],
  ]) {
    expect(design).toContain(`${name}: "${value}"`);
    expect(css).toMatch(new RegExp(`--${name}:\\s*${value}`));
  }
});
