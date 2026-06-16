import { TestBed } from '@angular/core/testing';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { StatusBadgeComponent } from './status-badge.component';

describe('StatusBadgeComponent — a11y', () => {
    function create(value: string) {
        TestBed.configureTestingModule({ imports: [StatusBadgeComponent] });
        const fixture = TestBed.createComponent(StatusBadgeComponent);
        fixture.componentInstance.value = value;
        fixture.detectChanges();
        return fixture;
    }

    for (const value of ['ERROR', 'WARN', 'INFO', 'SUCCESS', 'UNKNOWN']) {
        it(`has no axe violations for "${value}"`, async () => {
            const fixture = create(value);
            await expectNoA11yViolations(fixture.nativeElement);
        });
    }
});
