import { readFileSync, existsSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { it, expect } from "vitest";

const pages = ["faq", "contact", "complaints", "accessibility", "recording"];

it.each(pages)(
  "support/%s is readable without scripts and has resolvable navigation",
  (page) => {
    const path = resolve("public/support", `${page}.html`);
    const document = new DOMParser().parseFromString(
      readFileSync(path, "utf8"),
      "text/html",
    );
    expect(document.documentElement.lang).toBe("vi");
    expect(document.querySelector("h1")?.textContent).toBeTruthy();
    expect(document.querySelectorAll("script, iframe, form").length).toBe(0);
    expect(
      document
        .querySelector('meta[http-equiv="Content-Security-Policy"]')
        ?.getAttribute("content"),
    ).toContain("default-src 'none'");
    for (const link of document.querySelectorAll("[href]")) {
      const href = link.getAttribute("href")!;
      if (href.startsWith("mailto:")) {
        expect(href).toMatch(/^mailto:support@tnfastvn\.com(?:\?|$)/);
      } else {
        expect(href).not.toMatch(/^(?:[a-z]+:|\/\/)/i);
        expect(existsSync(resolve(dirname(path), href))).toBe(true);
      }
    }
    expect(document.body.textContent).not.toMatch(
      /Kiki|phản hồi.{0,20}24 giờ/i,
    );
  },
);
