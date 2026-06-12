import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { InspectoEmptyStateComponent } from './empty-state.component';

describe('InspectoEmptyStateComponent', () => {
    async function create(inputs: Partial<InspectoEmptyStateComponent>) {
        TestBed.configureTestingModule({
            imports: [InspectoEmptyStateComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(InspectoEmptyStateComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('renders the message, hides title and action by default', async () => {
        const fixture = await create({ message: 'Nothing here yet.' });
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('Nothing here yet.');
        expect(el.querySelector('button')).toBeNull();
    });

    it('renders title and emits action on button click', async () => {
        const fixture = await create({
            message: 'No pipelines.',
            title: 'All quiet',
            actionLabel: 'Refresh',
        });
        const emitted: boolean[] = [];
        fixture.componentInstance.action.subscribe(() => emitted.push(true));
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).toContain('All quiet');
        el.querySelector('button')!.click();
        expect(emitted.length).toBe(1);
    });
});
