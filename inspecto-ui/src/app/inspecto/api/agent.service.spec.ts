import { describe, expect, it } from 'vitest';
import { SseFrame, SseFrameParser } from './agent.service';

describe('SseFrameParser', () => {
    it('parses unnamed token frames, one per blank-line-terminated block', () => {
        const parser = new SseFrameParser();
        expect(parser.push('data: hello\n\ndata: world\n\n')).toEqual([
            { event: null, data: 'hello' },
            { event: null, data: 'world' },
        ] satisfies SseFrame[]);
    });

    it('buffers across arbitrary chunk boundaries', () => {
        const parser = new SseFrameParser();
        const frames: SseFrame[] = [];
        for (const chunk of ['da', 'ta: hel', 'lo\n', '\nda', 'ta: again\n\n']) frames.push(...parser.push(chunk));
        expect(frames).toEqual([
            { event: null, data: 'hello' },
            { event: null, data: 'again' },
        ]);
    });

    it('joins multiple data: lines of one frame with \\n (standard SSE multi-line data)', () => {
        const parser = new SseFrameParser();
        expect(parser.push('data: line one\ndata: line two\ndata:\n\n')).toEqual([
            { event: null, data: 'line one\nline two\n' },
        ]);
    });

    it('parses named frames (artifact / complete / error) with their event name', () => {
        const parser = new SseFrameParser();
        const artifact = '{"kind":"chart","config":{"type":"bar"}}';
        const complete = '{"kind":"TEXT","text":"done"}';
        const frames = parser.push(`event: artifact\ndata: ${artifact}\n\nevent: complete\ndata: ${complete}\n\nevent: error\ndata: boom\n\n`);
        expect(frames).toEqual([
            { event: 'artifact', data: artifact },
            { event: 'complete', data: complete },
            { event: 'error', data: 'boom' },
        ]);
        expect(JSON.parse(frames[0].data)).toEqual({ kind: 'chart', config: { type: 'bar' } });
    });

    it('resets the event name after each frame and tolerates CRLF line endings', () => {
        const parser = new SseFrameParser();
        expect(parser.push('event: artifact\r\ndata: {}\r\n\r\ndata: token\r\n\r\n')).toEqual([
            { event: 'artifact', data: '{}' },
            { event: null, data: 'token' }, // the name does not leak into the next frame
        ]);
    });

    it('ignores comments and unknown fields; an incomplete trailing frame is not emitted', () => {
        const parser = new SseFrameParser();
        expect(parser.push(': keep-alive\nid: 7\nretry: 100\n\n')).toEqual([]); // no data ⇒ no frame
        expect(parser.push('data: pending')).toEqual([]); // no terminator yet
        expect(parser.push('\n\n')).toEqual([{ event: null, data: 'pending' }]);
    });
});
