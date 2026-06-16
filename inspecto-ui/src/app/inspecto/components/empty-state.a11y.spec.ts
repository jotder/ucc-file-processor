import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoEmptyStateComponent } from './empty-state.component';

describe('InspectoEmptyStateComponent — a11y', () => {
    function create(inputs: Partial<InspectoEmptyStateComponent>) {
        TestBed.configureTestingModule({
            imports: [InspectoEmptyStateComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(InspectoEmptyStateComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('message-only has no axe violations', async () => {
        const fixture = create({ message: 'No events match the current filters.' });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('title + action button has no axe violations', async () => {
        const fixture = create({
            title: 'All quiet',
            message: 'Events will appear here as pipelines run.',
            actionLabel: 'Refresh',
        });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
