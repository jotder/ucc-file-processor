import { Injectable } from '@angular/core';

type DataUnit = 'MB' | 'GB' | 'TB' | 'PB';

@Injectable({
    providedIn: 'root',
})
export class UtilityService {

    formatMac(mac: string | null | undefined): string | undefined {
        if (!mac || mac === 'undefined') return undefined;
        return mac.match(/.{1,2}/g)!.join(':').toUpperCase();
    }

    formatLastSeen(lastSeen: string | null | undefined): string | undefined {
        if ( lastSeen === 'undefined') return undefined;
        const date = new Date(lastSeen);
        return this.timeAgo(date);
    }

    formatBytes(bytes: number, decimals: number): string {
        if (bytes === 0) return '0';
        const k = 1024;
        const sizes = ['B', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return `${parseFloat((bytes / Math.pow(k, i)).toFixed(decimals))} ${sizes[i]}`;
    }

    convertSpecifiedDataUnits(bytes: number, precision: number, toDataUnit: DataUnit): string | number {
        const KB = 1024;
        const MB = KB * 1024;
        const GB = MB * 1024;
        const TB = GB * 1024;
        const PB = TB * 1024;

        const unitMap: Record<DataUnit, number> = { MB, GB, TB, PB };
        const divisor = unitMap[toDataUnit];

        if (divisor && this.isNumeric(bytes) && bytes > 0) {
            return this.replaceNumberWithCommas((bytes / divisor).toFixed(precision));
        }
        return bytes;
    }

    replaceNumberWithCommas(val: string | number): string {
        const parts = val.toString().split('.');
        parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        return parts.join('.');
    }

    isNumeric(value: any): boolean {
        return !isNaN(value - parseFloat(value));
    }

    capitaliseWord(content: string): string {
        return content
            .split(' ')
            .filter((t) => t !== '')
            .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
            .join(' ');
    }

    getLabel(attrName: string | undefined): string {
        if (!attrName) return '';

        if (attrName.includes('-')) {
            attrName = attrName.replace(/-/g, ' ');
        } else if (attrName.includes('_')) {
            attrName = attrName.replace(/_/g, ' ');
        } else {
            attrName = attrName.replace(/([A-Z])/g, ' $1');
        }

        return this.capitaliseWord(attrName);
    }

    getDayNameCode(dayName: string): number | undefined {
        const dayMap: Record<string, number> = {
            sunday: 1,
            monday: 2,
            tuesday: 3,
            wednesday: 4,
            thursday: 5,
            friday: 6,
            saturday: 7,
        };
        return dayMap[dayName.trim().toLowerCase()];
    }

    roundOffTillTwo(value: number, exp: number): number {
        if (!exp || exp === 0) return Math.round(value);
        if (isNaN(value) || exp % 1 !== 0) return NaN;

        // Shift, round, shift back using exponential notation
        const shifted = Number(`${value}e${exp}`);
        const rounded = Math.round(shifted);
        return Number(`${rounded}e${-exp}`);
    }

    scaleNumber(inputY: number, yMin: number, yMax: number, xMin: number, xMax: number): number {
        if (inputY === 0) return 0;
        const percent = (inputY - yMin) / (yMax - yMin);
        return percent * (xMax - xMin) + xMin;
    }

    appendZeroInBeginning(value: string, digit: number): string {
        const padded = value.padStart(digit, '0');
        return `${padded.substring(0, 2)} - ${padded.substring(2)}`;
    }

    formatSessionId(session: string): string | undefined {
        const sessionMap: Record<string, string> = {
            '00 - 01': '0a',  '0001': '0a',
            '01 - 02': '1a',  '0102': '1a',
            '02 - 03': '2a',  '0203': '2a',
            '03 - 04': '3a',  '0304': '3a',
            '04 - 05': '4a',  '0405': '4a',
            '05 - 06': '5a',  '0506': '5a',
            '06 - 07': '6a',  '0607': '6a',
            '07 - 08': '7a',  '0708': '7a',
            '08 - 09': '8a',  '0809': '8a',
            '09 - 10': '9a',  '0910': '9a',
            '10 - 11': '10a', '1011': '10a',
            '11 - 12': '11a', '1112': '11a',
            '12 - 13': '12a', '1213': '12a',
            '13 - 14': '1p',  '1314': '1p',
            '14 - 15': '2p',  '1415': '2p',
            '15 - 16': '3p',  '1516': '3p',
            '16 - 17': '4p',  '1617': '4p',
            '17 - 18': '5p',  '1718': '5p',
            '18 - 19': '6p',  '1819': '6p',
            '19 - 20': '7p',  '1920': '7p',
            '20 - 21': '8p',  '2021': '8p',
            '21 - 22': '9p',  '2122': '9p',
            '22 - 23': '10p', '2223': '10p',
            '23 - 00': '11p', '2300': '11p',
        };
        return sessionMap[session];
    }

    countryBasedDateFormat(date: string | Date, format: string): string {
        // Uses Intl.DateTimeFormat — no moment dependency
        const parsed = typeof date === 'string'
            ? this.parseDateWithFormat(date, format)
            : date;
        return new Intl.DateTimeFormat(navigator.language, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
        }).format(parsed);
    }

    reformatNumberWithThousandSeparator(value: any): string {
        if (this.isNumeric(value)) {
            return Number(value).toLocaleString();
        }
        return value;
    }

    lastDayOfMonth(year: number, month: number): string {
        // Day 0 of next month = last day of current month
        const lastDay = new Date(year, month, 0).getDate();
        return String(lastDay).padStart(2, '0');
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private timeAgo(date: Date): string {
        const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
        const intervals: [number, string][] = [
            [31536000, 'year'],
            [2592000, 'month'],
            [86400, 'day'],
            [3600, 'hour'],
            [60, 'minute'],
        ];
        for (const [secs, label] of intervals) {
            const count = Math.floor(seconds / secs);
            if (count >= 1) return `${count} ${label}${count > 1 ? 's' : ''} ago`;
        }
        return 'just now';
    }

    private parseDateWithFormat(dateStr: string, _format: string): Date {
        // Fallback: native Date parse (works for ISO strings)
        // For strict format parsing, swap in date-fns `parse()` if needed
        return new Date(dateStr);
    }
}