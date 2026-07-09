import { JobParameterDecl } from 'app/inspecto/api';
import { AttributeSpec, AttributeType } from 'app/inspecto/component-model';

/**
 * Maps a Job Type's declared {@link JobParameterDecl}s (from `GET /jobs/types/{id}`, R3) onto
 * {@link AttributeSpec}s so `<inspecto-schema-form>` renders the type's runtime parameters as a typed,
 * labelled, validated form (Workbench → Jobs, job-framework P3c). This is what makes the jobs form
 * descriptor-driven — a new Job Type (e.g. a Job Pack's) surfaces its contract with no UI change.
 */

/** DuckDB/framework `ParamType` name → the schema-form input type. `sql` renders multiline. */
function attributeType(decl: JobParameterDecl): AttributeType {
    switch (decl.type) {
        case 'INTEGER':
        case 'DECIMAL':
            return 'number';
        case 'BOOLEAN':
            return 'boolean';
        default: // STRING | DATE | INSTANT | DATASET_REF
            return decl.name === 'sql' ? 'multiline' : 'string';
    }
}

/** Humanise a snake/kebab parameter name for the field label (`event_date` → `Event date`). */
function label(name: string): string {
    const words = name.replace(/[_-]+/g, ' ').trim();
    return words ? words.charAt(0).toUpperCase() + words.slice(1) : name;
}

/** One declared parameter → an {@link AttributeSpec}. Required ⇒ `required` tier; optional stays visible. */
export function paramDeclToSpec(decl: JobParameterDecl): AttributeSpec {
    const help = [decl.description, decl.deduce ? `Deduced as ${decl.deduce} when unset` : '']
        .filter(Boolean)
        .join(' · ');
    return {
        key: decl.name,
        label: label(decl.name),
        type: attributeType(decl),
        tier: decl.required ? 'required' : 'optional',
        required: decl.required,
        default: decl.default || undefined,
        help: help || undefined,
        placeholder: decl.deduce || undefined,
    };
}

export function paramDeclsToSpecs(decls: JobParameterDecl[]): AttributeSpec[] {
    return (decls ?? []).map(paramDeclToSpec);
}
