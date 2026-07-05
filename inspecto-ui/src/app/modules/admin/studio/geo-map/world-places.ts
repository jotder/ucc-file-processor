import { PlaceRecord } from 'app/inspecto/geo';

/**
 * A small bundled place table for the offline geocoder (geo plan D4). Covers major world cities plus the
 * cities the seeded demos / case studies revolve around (Dhaka, Dubai, remittance & mule corridors), so
 * "find place" genuinely lands somewhere useful with zero network. A customer swaps in a larger table (or
 * an online geocoder) through the same `Geocoder` seam later — this is deliberately minimal, not a gazetteer.
 */
export const WORLD_PLACES: PlaceRecord[] = [
    // Demo / case-study anchors
    { name: 'Dhaka', lat: 23.81, lon: 90.41, country: 'Bangladesh' },
    { name: 'Chittagong', lat: 22.36, lon: 91.78, country: 'Bangladesh' },
    { name: 'Dubai', lat: 25.2, lon: 55.27, country: 'UAE' },
    { name: 'Singapore', lat: 1.35, lon: 103.82, country: 'Singapore' },
    { name: 'Kolkata', lat: 22.57, lon: 88.36, country: 'India' },
    // Major world cities
    { name: 'London', lat: 51.51, lon: -0.13, country: 'United Kingdom' },
    { name: 'New York', lat: 40.71, lon: -74.01, country: 'United States' },
    { name: 'Los Angeles', lat: 34.05, lon: -118.24, country: 'United States' },
    { name: 'Chicago', lat: 41.88, lon: -87.63, country: 'United States' },
    { name: 'Toronto', lat: 43.65, lon: -79.38, country: 'Canada' },
    { name: 'Mexico City', lat: 19.43, lon: -99.13, country: 'Mexico' },
    { name: 'São Paulo', lat: -23.55, lon: -46.63, country: 'Brazil' },
    { name: 'Buenos Aires', lat: -34.6, lon: -58.38, country: 'Argentina' },
    { name: 'Paris', lat: 48.86, lon: 2.35, country: 'France' },
    { name: 'Berlin', lat: 52.52, lon: 13.41, country: 'Germany' },
    { name: 'Madrid', lat: 40.42, lon: -3.7, country: 'Spain' },
    { name: 'Rome', lat: 41.9, lon: 12.5, country: 'Italy' },
    { name: 'Moscow', lat: 55.76, lon: 37.62, country: 'Russia' },
    { name: 'Istanbul', lat: 41.01, lon: 28.98, country: 'Turkey' },
    { name: 'Cairo', lat: 30.04, lon: 31.24, country: 'Egypt' },
    { name: 'Lagos', lat: 6.52, lon: 3.38, country: 'Nigeria' },
    { name: 'Nairobi', lat: -1.29, lon: 36.82, country: 'Kenya' },
    { name: 'Johannesburg', lat: -26.2, lon: 28.05, country: 'South Africa' },
    { name: 'Mumbai', lat: 19.08, lon: 72.88, country: 'India' },
    { name: 'Delhi', lat: 28.61, lon: 77.21, country: 'India' },
    { name: 'Karachi', lat: 24.86, lon: 67.0, country: 'Pakistan' },
    { name: 'Bangkok', lat: 13.76, lon: 100.5, country: 'Thailand' },
    { name: 'Jakarta', lat: -6.21, lon: 106.85, country: 'Indonesia' },
    { name: 'Beijing', lat: 39.9, lon: 116.41, country: 'China' },
    { name: 'Shanghai', lat: 31.23, lon: 121.47, country: 'China' },
    { name: 'Hong Kong', lat: 22.32, lon: 114.17, country: 'China' },
    { name: 'Tokyo', lat: 35.68, lon: 139.69, country: 'Japan' },
    { name: 'Seoul', lat: 37.57, lon: 126.98, country: 'South Korea' },
    { name: 'Sydney', lat: -33.87, lon: 151.21, country: 'Australia' },
    { name: 'Melbourne', lat: -37.81, lon: 144.96, country: 'Australia' },
];
