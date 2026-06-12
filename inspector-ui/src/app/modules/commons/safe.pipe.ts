import { inject, Pipe, PipeTransform } from '@angular/core';
import {
    DomSanitizer,
    SafeHtml,
    SafeStyle,
    SafeScript,
    SafeUrl,
    SafeResourceUrl,
} from '@angular/platform-browser';

type SafeType = 'html' | 'style' | 'script' | 'url' | 'resourceUrl';
type SafeValue = SafeHtml | SafeStyle | SafeScript | SafeUrl | SafeResourceUrl;

@Pipe({
    name: 'safe',
    standalone: true,
    pure: true,
})
export class SafePipe implements PipeTransform {
    private readonly sanitizer = inject(DomSanitizer);

    transform(value: string, type: SafeType): SafeValue {
        switch (type) {
            case 'html':        return this.sanitizer.bypassSecurityTrustHtml(value);
            case 'style':       return this.sanitizer.bypassSecurityTrustStyle(value);
            case 'script':      return this.sanitizer.bypassSecurityTrustScript(value);
            case 'url':         return this.sanitizer.bypassSecurityTrustUrl(value);
            case 'resourceUrl': return this.sanitizer.bypassSecurityTrustResourceUrl(value);
        }
    }
}