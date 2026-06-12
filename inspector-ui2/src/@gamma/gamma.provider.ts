import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
    EnvironmentProviders,
    Provider,
    importProvidersFrom,
    inject,
    provideAppInitializer,
    provideEnvironmentInitializer,
} from '@angular/core';
import { MatDialogModule } from '@angular/material/dialog';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import {
    GAMMA_MOCK_API_DEFAULT_DELAY,
    mockApiInterceptor,
} from '@gamma/lib/mock-api';
import { GammaConfig } from '@gamma/services/config';
import { GAMMA_CONFIG } from '@gamma/services/config/config.constants';
import { GammaConfirmationService } from '@gamma/services/confirmation';
import {
    GammaLoadingService,
    gammaLoadingInterceptor,
} from '@gamma/services/loading';
import { GammaMediaWatcherService } from '@gamma/services/media-watcher';
import { GammaPlatformService } from '@gamma/services/platform';
import { GammaSplashScreenService } from '@gamma/services/splash-screen';
import { GammaUtilsService } from '@gamma/services/utils';

export type GammaProviderConfig = {
    mockApi?: {
        delay?: number;
        service?: any;
    };
    gamma?: GammaConfig;
};

/**
 * Gamma provider
 */
export const provideGamma = (
    config: GammaProviderConfig
): Array<Provider | EnvironmentProviders> => {
    // Base providers
    const providers: Array<Provider | EnvironmentProviders> = [
        
        {
            // Use the 'fill' appearance on Angular Material form fields by default
            provide: MAT_FORM_FIELD_DEFAULT_OPTIONS,
            useValue: {
                appearance: 'fill',
            },
        },
        {
            provide: GAMMA_MOCK_API_DEFAULT_DELAY,
            useValue: config?.mockApi?.delay ?? 0,
        },
        {
            provide: GAMMA_CONFIG,
            useValue: config?.gamma ?? {},
        },

        importProvidersFrom(MatDialogModule),
        provideEnvironmentInitializer(() => inject(GammaConfirmationService)),

        provideHttpClient(withInterceptors([gammaLoadingInterceptor])),
        provideEnvironmentInitializer(() => inject(GammaLoadingService)),

        provideEnvironmentInitializer(() => inject(GammaMediaWatcherService)),
        provideEnvironmentInitializer(() => inject(GammaPlatformService)),
        provideEnvironmentInitializer(() => inject(GammaSplashScreenService)),
        provideEnvironmentInitializer(() => inject(GammaUtilsService)),
    ];

    // Mock Api services
    if (config?.mockApi?.service) {
        providers.push(
            provideHttpClient(withInterceptors([mockApiInterceptor])),
            provideAppInitializer(() => {
                const mockApiService = inject(config.mockApi.service);
            })
        );
    }

    // Return the providers
    return providers;
};
