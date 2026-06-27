import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { columnType, operatorDef, operatorsFor, OperatorDef } from './query-columns';
import { ColumnMeta, Condition, ConditionGroup, Operator, emptyGroup, newCondition } from './query-types';

/**
 * Recursive editor for one {@link ConditionGroup} (AND/OR over conditions + nested groups). Mutates the
 * bound group object in place and emits {@link changed} after every edit; the host ({@link QueryPanelComponent})
 * re-derives SQL + preview from that. Renders itself recursively via its own selector (a standalone
 * component may reference its own selector without listing itself in `imports`).
 */
@Component({
    selector: 'inspecto-query-condition-group',
    standalone: true,
    imports: [
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './query-condition-group.component.html',
})
export class QueryConditionGroupComponent {
    @Input({ required: true }) group!: ConditionGroup;
    @Input() columns: ColumnMeta[] = [];
    @Input() root = false;
    @Output() changed = new EventEmitter<void>();

    setOp(op: 'AND' | 'OR'): void {
        this.group.op = op;
        this.changed.emit();
    }

    addCondition(): void {
        this.group.items.push(newCondition(this.columns[0]?.name ?? ''));
        this.changed.emit();
    }

    addGroup(): void {
        this.group.items.push(emptyGroup('AND'));
        this.changed.emit();
    }

    removeAt(i: number): void {
        this.group.items.splice(i, 1);
        this.changed.emit();
    }

    asGroup(it: Condition | ConditionGroup): ConditionGroup {
        return it as ConditionGroup;
    }
    asCondition(it: Condition | ConditionGroup): Condition {
        return it as Condition;
    }

    operatorsForCondition(c: Condition): OperatorDef[] {
        return operatorsFor(columnType(this.columns, c.field));
    }
    arity(c: Condition): 0 | 1 | 2 | 'list' {
        return operatorDef(columnType(this.columns, c.field), c.operator)?.arity ?? 1;
    }
    inputType(c: Condition): string {
        return columnType(this.columns, c.field) === 'number' ? 'number' : 'text';
    }

    onFieldChange(c: Condition, field: string): void {
        c.field = field;
        const ops = operatorsFor(columnType(this.columns, field));
        if (!ops.some((o) => o.op === c.operator)) c.operator = ops[0]?.op ?? '=';
        c.value = '';
        c.value2 = '';
        this.changed.emit();
    }
    onOperatorChange(c: Condition, op: Operator): void {
        c.operator = op;
        this.changed.emit();
    }
    onValue(c: Condition, v: string): void {
        c.value = v;
        this.changed.emit();
    }
    onValue2(c: Condition, v: string): void {
        c.value2 = v;
        this.changed.emit();
    }
}
