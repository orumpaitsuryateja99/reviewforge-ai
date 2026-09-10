import { describe, expect, it } from "vitest";
import { parseUnifiedDiff } from "@/lib/diff";

describe("parseUnifiedDiff", () => {
  const patch = [
    "@@ -10,4 +10,6 @@ class OrderService {",
    "     void reserve(String sku, int quantity) {",
    "-        inventory.reserve(sku, quantity);",
    "+        if (inventory.available(sku)) {",
    "+            inventory.reserve(sku, quantity);",
    "+        }",
    "     }",
  ].join("\n");

  it("numbers context lines against both revisions", () => {
    const lines = parseUnifiedDiff(patch);
    const context = lines.filter((line) => line.kind === "context");

    // The removed line consumes base line 11, so the trailing context sits at base 12 / head 14.
    expect(context[0]).toMatchObject({ oldLine: 10, newLine: 10 });
    expect(context[1]).toMatchObject({ oldLine: 12, newLine: 14 });
  });

  it("numbers additions on the head revision only", () => {
    const additions = parseUnifiedDiff(patch).filter((line) => line.kind === "addition");

    expect(additions.map((line) => line.newLine)).toEqual([11, 12, 13]);
    expect(additions.every((line) => line.oldLine === null)).toBe(true);
    expect(additions[0].content).toBe("        if (inventory.available(sku)) {");
  });

  it("numbers deletions on the base revision only", () => {
    const deletions = parseUnifiedDiff(patch).filter((line) => line.kind === "deletion");

    expect(deletions).toHaveLength(1);
    expect(deletions[0]).toMatchObject({ oldLine: 11, newLine: null, marker: "−" });
  });

  it("keeps hunk headers as metadata without line numbers", () => {
    const [first] = parseUnifiedDiff(patch);

    expect(first.kind).toBe("metadata");
    expect(first.oldLine).toBeNull();
    expect(first.newLine).toBeNull();
    expect(first.content).toContain("@@ -10,4 +10,6 @@");
  });

  it("treats file headers of a new-file patch as metadata", () => {
    const lines = parseUnifiedDiff(
      ["--- /dev/null", "+++ b/src/test/java/A.java", "@@ -0,0 +1,2 @@", "+class A {", "+}"].join("\n"),
    );

    expect(lines.slice(0, 3).every((line) => line.kind === "metadata")).toBe(true);
    expect(lines.filter((line) => line.kind === "addition").map((line) => line.newLine)).toEqual([1, 2]);
  });
});
