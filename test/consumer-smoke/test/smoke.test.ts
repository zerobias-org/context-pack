/**
 * Consumer smoke fixture — test half.
 *
 * Mirrors how a module's e2e suite consumes the pack:
 *   - `describe` / `it` come from the mocha ambient types, which only resolve
 *     when tsconfig lists BOTH node and mocha in `types`. Narrowing to
 *     [node] compiles src/ but fails here with TS2593.
 *   - `chai` is imported by source, so it must resolve from the CONSUMER's
 *     node_modules. When it was a plain pack dependency npm nested it and
 *     this import failed with TS2307.
 *   - the assertion style is chai 4.x; a chai major with a changed API
 *     breaks here rather than in 293 modules.
 */

import { expect } from 'chai';
import { describeChunk, encode, parseHost, streamOf } from '../src/index.js';

describe('consumer smoke', function () {
  it('resolves the node ambient globals', function () {
    expect(encode('hello')).to.be.instanceOf(Buffer);
    expect(encode('hello').toString('utf8')).to.equal('hello');
  });

  it('resolves URL', function () {
    expect(parseHost('https://example.com/path')).to.equal('example.com');
  });

  it('resolves node:stream', function () {
    expect(streamOf('x')).to.have.property('pipe');
  });

  it('accepts a generic ReadableStream type', function () {
    expect(describeChunk(undefined)).to.equal('none');
  });
});
