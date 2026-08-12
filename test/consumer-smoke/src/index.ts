/**
 * Consumer smoke fixture — source half.
 *
 * Deliberately exercises the ambient globals that a toolchain change has
 * broken before. Each line here corresponds to a real failure:
 *
 *   Buffer / node:stream  — @types/node 24 stopped injecting ambient globals;
 *                           without `types: [node]` these became TS2591
 *   URL                   — same change, surfaced as TS2304
 *   ReadableStream<T>     — needs the DOM lib for the GENERIC declaration;
 *                           without it @smithy/core failed with TS2315
 *                           "Type 'ReadableStream' is not generic"
 *
 * If a pack update regresses lib/types resolution, this file stops compiling.
 */

import { Readable } from 'node:stream';

export function encode(value: string): Buffer {
  return Buffer.from(value, 'utf8');
}

export function parseHost(raw: string): string {
  return new URL(raw).host;
}

export function streamOf(value: string): Readable {
  return Readable.from([value]);
}

/** Generic web-stream reference — requires the DOM lib. */
export type ChunkStream<T> = ReadableStream<T>;

export function describeChunk<T>(_stream: ChunkStream<T> | undefined): string {
  return _stream === undefined ? 'none' : 'stream';
}
