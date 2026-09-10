export type DiffLine = {
  kind: "addition" | "deletion" | "context" | "metadata";
  marker: string;
  content: string;
  oldLine: number | null;
  newLine: number | null;
};

const HUNK_HEADER = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/;

export function parseUnifiedDiff(patch: string): DiffLine[] {
  let oldLine = 0;
  let newLine = 0;

  return patch.split("\n").map((rawLine) => {
    const hunk = rawLine.match(HUNK_HEADER);
    if (hunk) {
      oldLine = Number(hunk[1]);
      newLine = Number(hunk[2]);
      return line("metadata", "", rawLine, null, null);
    }

    if (rawLine.startsWith("+") && !rawLine.startsWith("+++")) {
      return line("addition", "+", rawLine.slice(1), null, newLine++);
    }
    if (rawLine.startsWith("-") && !rawLine.startsWith("---")) {
      return line("deletion", "−", rawLine.slice(1), oldLine++, null);
    }
    if (rawLine.startsWith(" ")) {
      return line("context", " ", rawLine.slice(1), oldLine++, newLine++);
    }
    return line("metadata", "", rawLine, null, null);
  });
}

function line(
  kind: DiffLine["kind"],
  marker: string,
  content: string,
  oldLine: number | null,
  newLine: number | null,
): DiffLine {
  return { kind, marker, content, oldLine, newLine };
}
