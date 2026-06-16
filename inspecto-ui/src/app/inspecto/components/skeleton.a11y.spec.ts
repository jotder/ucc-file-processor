import { TestBed } from '@angular/core/testing';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSkeletonComponent } from './skeleton.component';

describe('InspectoSkeletonComponent — a11y', () => {
    function create(inputs: Partial<InspectoSkeletonComponent>) {
        TestBed.configureTestingModule({ imports: [InspectoSkeletonComponent] });
        const fixture = TestBed.createComponent(InspectoSkeletonComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('single block has no axe violations and is hidden from the a11y tree', async () => {
        const fixture = create({ width: '40%', height: '0.875rem' });
        expect(fixture.nativeElement.getAttribute('aria-hidden')).toBe('true');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('multi-line paragraph has no axe violations', async () => {
        const fixture = create({ lines: 4 });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
