// Parses every ```mermaid block of the Markdown files given as arguments with the parser of the
// Mermaid library, and fails on a file without a block or with a block the parser rejects.
// Mermaid needs a DOM to load, which jsdom supplies. CI runs it as:
//
//   npm install --prefix "$RUNNER_TEMP/mermaid" mermaid@11 jsdom@29
//   cp .github/scripts/parse-mermaid.mjs "$RUNNER_TEMP/mermaid/"
//   node "$RUNNER_TEMP/mermaid/parse-mermaid.mjs" examples/README.md ...
import { readFileSync } from 'node:fs';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!DOCTYPE html><body></body>');
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.DOMParser = dom.window.DOMParser;
const mermaid = (await import('mermaid')).default;

let parsed = 0;
let failures = 0;
for (const file of process.argv.slice(2)) {
  const text = readFileSync(file, 'utf8');
  const blocks = [...text.matchAll(/^```mermaid\n([\s\S]*?)^```$/gm)].map((match) => match[1]);
  if (blocks.length === 0) {
    failures++;
    console.error(`${file}: no mermaid block`);
  }
  for (const [index, block] of blocks.entries()) {
    try {
      const result = await mermaid.parse(block);
      parsed++;
      console.log(`${file} block ${index + 1}: ${result.diagramType}`);
    } catch (error) {
      failures++;
      console.error(`${file} block ${index + 1}: ${error.message ?? error}\n${block}`);
    }
  }
}
console.log(`${parsed} diagrams parsed, ${failures} failures`);
process.exit(failures === 0 ? 0 : 1);
