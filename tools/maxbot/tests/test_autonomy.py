import copy, hashlib, hmac, importlib.util, json, pathlib, sys, unittest
import numpy as np
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'tools/maxbot'))
import planner, configure

def request():
    c=configure.strict_load(ROOT/'maxbot/config/profiles/app-defaults.v1.json');configure.import_calibration(c,configure.strict_load(ROOT/'firmware/calibration/calibration.json'));c['firmwareCalibrationId']=configure.actuator_id(c);c['geometryId']='b'*64
    course={'frozen':True,'mapEpoch':'fixture','frameId':'launch-rear-axle-v1','cameraId':'c'*64,'modelId':'d'*64,'cones':[{'x':x,'y':0,'observed':True,'errorBoundM':.001,'independentViews':2} for x in [2,4,6,8]]}
    m={'schemaVersion':1,'revision':1,'reviewed':True,'provenance':['SYNTHETIC HOST TEST; never deploy'],'steeringRateRadS':2.,'steeringLagS':.05,'applicationDelayS':.02,'vioLagS':.02,'accelerationMps2':.3,'brakingMps2':.5,'lateralMps2':.3,'speedPwm':[[0,0],[.1,120],[.5,200]],'validSpeedRangeMps':[0,.5]}
    return {'schemaVersion':1,'stopped':True,'calibration':c,'vehicleModel':m,'course':course,'floorBoundsM':[-1,11,-4,4],'coneRadiusM':c['cones']['baseDiameterM']/2,'uncertaintyM':0,'clearanceM':.15,'taskSpeedMps':.2}

def feasible_fixture(req):
    # Independent smooth wide-spacing path; analytic derivatives then arc-length samples.
    from scipy.interpolate import CubicSpline
    spline=CubicSpline([0,1,2,4,6,8,9,10],[0,-.1,-.42,.42,-.42,.42,.42,.42],bc_type=((1,0),(1,0)))
    x=np.linspace(0,10,10001);y=spline(x);dy=spline(x,1);ddy=spline(x,2);s=np.r_[0,np.cumsum(np.hypot(np.diff(x),np.diff(y)))];distance=np.arange(0,s[-1],.02)
    xs=np.interp(distance,s,x);ys=spline(xs);yaw=np.arctan(spline(xs,1));k=spline(xs,2)/(1+spline(xs,1)**2)**1.5
    path=np.column_stack([xs,ys,yaw,k,distance]);speed=planner.retime(path,req);return path,speed

class AutonomyTest(unittest.TestCase):
    def test_migration_and_private_precedence(self):
        c=request()['calibration'];baseline=copy.deepcopy(c);baseline['car']['rearOverhangM']=None
        self.assertEqual(configure.actuator_id(c),configure.actuator_id(baseline));self.assertEqual(configure.pwm_ceiling(c),170)
        priors=configure.strict_load(ROOT/'maxbot/config/profiles/course.v1.json');self.assertEqual(priors['centerSpacingRangeM'],[.5,1.]);self.assertTrue(all(p['x'] is None for p in priors['cones']));self.assertFalse(priors['observed'])
        c['car']['widthM']=float('nan')
        with self.assertRaises(ValueError):configure.validate_config(c)
        c['schemaVersion']=2
        with self.assertRaises(ValueError):configure.validate_config(c)
    def test_benchmark_missing_data_and_first_consumed_frame(self):
        import benchmark
        base=1_000_000_000
        events=[]
        for i in range(1,101):
            events.append({'type':'command_sent','boot':'b','session':'s','seq':i,'source_state':str(base),'source_frame':str(base),'policy_output_ns':str(base)})
            events.append({'type':'applied','boot':'b','session':'s','seq':i,'mapped_phone_applied_ns':str(base+i*1_000_000),'clock_uncertainty_ms':1,'applied_ms':i,'expires_ms':200})
        report=benchmark.summarize(events)
        self.assertEqual(report['imageAvailableToPwmMs']['samples'],1);self.assertEqual(report['policyToPwmMs']['p95'],95);self.assertIsNone(report['captureToPwm']);self.assertFalse(report['deviceBenchmarkComplete'])
        self.assertIsNone(benchmark.summarize([])['policyToPwmMs'])
    def test_canonical_artifact_set_rejects_bad_schemas(self):
        import artifacts,tempfile
        with tempfile.TemporaryDirectory() as directory:
            out=pathlib.Path(directory)/'out'
            manifest=artifacts.package([('policy',ROOT/'maxbot/policies/cone-slalom/policy.v1.json')],out,1)
            self.assertEqual(manifest['files'][0]['sha256'],hashlib.sha256((out/'policy.v1.json').read_bytes()).hexdigest())
            bad=pathlib.Path(directory)/'bad.json';bad.write_text('{"schemaVersion":2}')
            with self.assertRaises(Exception):artifacts.package([('policy',bad)],out,2)
    def test_protocol_golden(self):
        f=configure.strict_load(ROOT/'maxbot/config/fixtures/protocol-golden.v1.json')
        for message,key,result in [('canonicalMotion','key','motionMac'),('canonicalStop','operatorKey','stopMac')]:self.assertEqual(hmac.new(f[key].encode(),f[message].encode(),hashlib.sha256).hexdigest(),f[result])
    def test_legacy_nominal_steering_regression(self):
        v=planner.legacy_requirement(request()['calibration']);self.assertAlmostEqual(v['steeringDeg'],51.2,delta=.15);self.assertGreater(v['steeringDeg'],v['limitDeg'])
    def test_unknown_measurements_and_prior_map_rejected(self):
        req=request();req['vehicleModel']['brakingMps2']=None
        with self.assertRaisesRegex(ValueError,'braking'):planner.plan(req)
        req=request();req['course']['cones'][0]['observed']=False
        with self.assertRaisesRegex(ValueError,'Independent'):planner.plan(req)
        req=request();req['clearanceM']=.10
        with self.assertRaisesRegex(ValueError,'150'):planner.plan(req)
        req=request();req['stopped']=False
        with self.assertRaises(ValueError):planner.plan(req)
    def test_feasible_independent_continuous_validation(self):
        req=request();path,speed=feasible_fixture(req);report=planner.validate_path(path,speed,req)
        self.assertTrue(report['feasible'],report);self.assertLess(report['finishS'],path[-1,4]);self.assertEqual(speed[-1],0)
    def test_between_sample_collision_and_wrong_side(self):
        req=request();path,speed=feasible_fixture(req);req['course']['cones'][0].update(x=float((path[100,0]+path[101,0])/2),y=float((path[100,1]+path[101,1])/2));report=planner.validate_path(path,speed,req)
        self.assertFalse(report['feasible']);self.assertIn('Continuous footprint clearance',report['diagnostics'])
        req=request();path,speed=feasible_fixture(req);path[:,1]*=-1;path[:,2]*=-1;path[:,3]*=-1;self.assertIn('Wrong side',planner.validate_path(path,speed,req)['diagnostics'])
    def test_braking_tail_and_rotated_rear_clearance(self):
        req=request();path,speed=feasible_fixture(req);speed[-1]=.2;self.assertIn('Launch/braking tail speed',planner.validate_path(path,speed,req)['diagnostics'])
        car=req['calibration']['car'];self.assertAlmostEqual(planner.minimum_x([1,0,0],car),.945);self.assertLess(planner.minimum_x([1,0,np.pi/2],car),1-.055)
    def test_schema_bounds_and_nonfinite_timestamp_serialization(self):
        import jsonschema
        schema=configure.strict_load(ROOT/'maxbot/config/schemas/motion-command.v2.schema.json')
        fixture={'v':2,'type':'command','boot':'boot','session':'session','epoch':1,'seq':1,'issued_ms':1000,'expires_ms':1100,'mode':'DRIVE','steering_mdeg':1000,'pwm':120,'source_state':'999999999999999999','source_frame':'8','calibration_id':'a'*64,'mac':'b'*64};jsonschema.validate(fixture,schema)
        fixture['pwm']=256
        with self.assertRaises(jsonschema.ValidationError):jsonschema.validate(fixture,schema)
        fixture['pwm']=120;fixture['seq']=2**53
        with self.assertRaises(jsonschema.ValidationError):jsonschema.validate(fixture,schema)
if __name__=='__main__':unittest.main()
