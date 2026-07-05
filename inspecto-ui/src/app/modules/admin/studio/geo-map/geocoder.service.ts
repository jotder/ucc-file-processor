import { Injectable } from '@angular/core';
import { GeocodeResult, Geocoder, OfflineGeocoder } from 'app/inspecto/geo';
import { WORLD_PLACES } from './world-places';

/**
 * Root factory holding the available {@link Geocoder}s in stable order (mirrors GeoSourcesService /
 * GraphSourcesService). Ships one: the offline place-table geocoder (decision D4). A customer's online
 * geocoder (Nominatim URL) would join this list behind the same interface — swappable, no consumer change.
 */
@Injectable({ providedIn: 'root' })
export class GeocoderService {
    readonly geocoders: Geocoder[] = [new OfflineGeocoder(WORLD_PLACES)];

    /** The active geocoder (first available). */
    get active(): Geocoder {
        return this.geocoders[0];
    }

    geocode(query: string): Promise<GeocodeResult[]> {
        return this.active.geocode(query);
    }
}
