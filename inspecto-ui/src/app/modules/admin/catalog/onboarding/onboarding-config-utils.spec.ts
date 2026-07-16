import { describe, expect, it } from 'vitest';
import { clearMissingRoots, flattenBlock, mergeBlock, nestKeys } from './onboarding-config-utils';

describe('onboarding-config-utils', () => {
    it('flattens nested blocks to __-separated keys, joining lists', () => {
        expect(
            flattenBlock({ connector: 'sftp', duplicate: { mode: 'checksum' }, include: ['*.csv', '*.txt'] }),
        ).toEqual({ connector: 'sftp', duplicate__mode: 'checksum', include: '*.csv,*.txt' });
    });

    it('nests flat keys, splits list keys and prunes blanks', () => {
        expect(
            nestKeys({ connector: 'sftp', duplicate__mode: 'checksum', include: ' *.csv , *.txt ', exclude: '', connection: null }),
        ).toEqual({ connector: 'sftp', duplicate: { mode: 'checksum' }, include: ['*.csv', '*.txt'] });
    });

    it('round-trips flatten → nest', () => {
        const block = { connector: 'local', discovery: 'poll', post_action: { on_success: 'MOVE', archive_path: 'arch' } };
        expect(nestKeys(flattenBlock(block))).toEqual(block);
    });

    it('deep-merges patches, deletes undefined keys, keeps unknown keys', () => {
        const base = { name: 'x', collector: { connector: 'sftp', fetch: { mode: 'parallel' } }, parsing: { frontend: 'json' } };
        const next = mergeBlock(base, { collector: { connector: 'local', connection: undefined }, parsing: undefined });
        expect(next).toEqual({ name: 'x', collector: { connector: 'local', fetch: { mode: 'parallel' } } });
        expect(base.parsing).toEqual({ frontend: 'json' }); // input untouched
    });

    it('clearMissingRoots marks owned-but-absent roots for deletion only', () => {
        const nested = clearMissingRoots({ connector: 'local' }, ['connector', 'duplicate']);
        expect(nested).toEqual({ connector: 'local', duplicate: undefined });
        expect(mergeBlock({ duplicate: { mode: 'path' }, fetch: { mode: 'seq' } }, nested)).toEqual({
            connector: 'local',
            fetch: { mode: 'seq' },
        });
    });
});
