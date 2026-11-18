import importlib.util,json,pathlib,tempfile,unittest,math
ROOT=pathlib.Path(__file__).resolve().parents[3]
def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/f'tools/maxbot/{name}.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
analysis=module('experiment_analysis');policy=module('policy_search')
class ExperimentsTest(unittest.TestCase):
    def test_interval_arithmetic_preserves_uncertainty(self):
        self.assertEqual(analysis.interval_difference([10,20],[40,50]),[20,40])
        with self.assertRaises(ValueError):analysis.interval_difference([20,10],[30,40])
    def test_missing_physical_endpoint_stays_unavailable(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d)
            (root/'manifest.json').write_text(json.dumps({'id':'fixture','recipe':'camera_clock'}));(root/'summary.json').write_text(json.dumps({'outcome':'completed','complete':False}));(root/'events.jsonl').write_text('{"type":"step"}\n')
            result=analysis.analyse(root);self.assertIsNone(result['physical_latency_ms']);self.assertFalse(result['physical_validation']);self.assertTrue(result['warnings'])
    def maneuvers(self):
        result=[]
        for i in range(6):
            rows=[];v=0.;steer=0.
            for n in range(400):
                t=n*.02;pwm=[0,110,120,130,140,0][min(5,n//70)];target=10 if n%100<50 else -10
                rows.append({'time_s':t,'pwm':pwm,'speed_mps':v,'target_deg':target,'steering_deg':steer})
                delayed=policy.delayed(rows,t-.06,'pwm');v+=(max(0,delayed-50)*.002-v)*.02/.25;steer+=(policy.delayed(rows,t-.06,'target_deg')-steer)*.02/.15
            result.append({'session':f's{i}','split':'train' if i<4 else 'validation','reviewed':True,'alignment_uncertainty_ms':5,'samples':rows})
        return result
    def test_dynamics_fit_recovers_known_delay_and_lag_on_independent_sessions(self):
        result=policy.fit_model(self.maneuvers());self.assertTrue(result['validated_for_simulation']);self.assertAlmostEqual(result['application_delay_s'],.06,delta=.021);self.assertAlmostEqual(result['traction_tau_s'],.25,delta=.04)
    def test_leaking_sessions_or_single_pwm_level_are_rejected(self):
        data=self.maneuvers();data[-1]['session']='s0'
        with self.assertRaises(ValueError):policy.fit_model(data)
        data=self.maneuvers()
        for m in data:
            for row in m['samples']:row['pwm']=120
        with self.assertRaises(ValueError):policy.fit_model(data)
if __name__=='__main__':unittest.main()
