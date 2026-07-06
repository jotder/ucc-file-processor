import { Routes } from '@angular/router';
import { DecisionRulesComponent } from './decision-rules.component';
import './decision-rule.kind'; // R5: register the decision-rule ComponentKind (side-effect, guarded)

export default [{ path: '', component: DecisionRulesComponent }] as Routes;
