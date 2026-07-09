import { describe, expect, it } from 'vitest';
import { paramDeclToSpec, paramDeclsToSpecs } from './job-parameter-specs';

describe('paramDeclToSpec', () => {
    it('maps a required STRING param to a required-tier string field with a humanised label', () => {
        const s = paramDeclToSpec({ name: 'sink_dataset', type: 'STRING', required: true, deduce: '', default: '', description: 'Output' });
        expect(s).toMatchObject({ key: 'sink_dataset', label: 'Sink dataset', type: 'string', tier: 'required', required: true });
    });

    it('renders sql as multiline and INTEGER as a number field', () => {
        expect(paramDeclToSpec({ name: 'sql', type: 'STRING', required: true, deduce: '', default: '', description: '' }).type).toBe('multiline');
        expect(paramDeclToSpec({ name: 'retention_days', type: 'INTEGER', required: false, deduce: '', default: '7', description: '' }).type).toBe('number');
    });

    it('surfaces the deduce as placeholder + help and the default as the field default', () => {
        const s = paramDeclToSpec({ name: 'event_date', type: 'DATE', required: true, deduce: '$day(-1)', default: '', description: 'Business date' });
        expect(s.placeholder).toBe('$day(-1)');
        expect(s.help).toContain('$day(-1)');
        expect(s.help).toContain('Business date');
    });

    it('maps an optional param to the optional tier carrying its default', () => {
        const s = paramDeclToSpec({ name: 'scope', type: 'STRING', required: false, deduce: '', default: 'status', description: '' });
        expect(s.tier).toBe('optional');
        expect(s.required).toBe(false);
        expect(s.default).toBe('status');
    });

    it('maps a list of decls in order', () => {
        const specs = paramDeclsToSpecs([
            { name: 'sql', type: 'STRING', required: true, deduce: '', default: '', description: '' },
            { name: 'sources', type: 'STRING', required: false, deduce: '', default: '', description: '' },
        ]);
        expect(specs.map((s) => s.key)).toEqual(['sql', 'sources']);
    });
});
