import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
import contextlib
import io

ROOT=pathlib.Path(__file__).resolve().parents[3]

def module(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/f'tools/maxbot/{name}.py')
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

configure=module('configure');dataset=module('dataset')

class ToolsTest(unittest.TestCase):
    def test_readme_parameters_are_valid_and_disarmed(self):
        c=configure.read_config((ROOT/'README.md').read_text());header,ready=configure.firmware_header(c)
        self.assertFalse(ready);self.assertIn('#define MAXBOT_CALIBRATED 0',header)
        self.assertIsNone(c['steering']['adc']);self.assertFalse(c['commissioning']['steeringMeasured']);self.assertEqual(c['cones']['count'],4)
        self.assertEqual(c['car']['wheelbaseM'],.173)
        self.assertEqual([c['hardware'][k] for k in ['driveForwardPin','driveReversePin','steerForwardPin','steerReversePin','steerPotPin']],[4,16,22,23,35])

    def test_calibration_import_preserves_credentials(self):
        c={'network':{'pairingKey':'private'},'hardware':{'wifiSsid':'private'}}
        configure.import_calibration(c,{'hardware':{'drivePolarity':-1}})
        self.assertEqual(c['network']['pairingKey'],'private');self.assertEqual(c['hardware']['wifiSsid'],'private')
        configure.import_calibration(c,{'camera':{'heightM':.09924}})
        self.assertEqual(c['camera']['heightM'],.09924)
        for bad in [{'network':{}},{'hardware':{'wifiPassword':'bad'}},{'steering':None}]:
            with self.assertRaises(ValueError):configure.import_calibration(c,bad)

    def test_saved_photo_calibration_is_loaded_before_private_overrides(self):
        recorded=json.loads((ROOT/'firmware/calibration/calibration.json').read_text())
        self.assertEqual(recorded['steering']['adc'],[1158,975,849,785,688,608,461])
        self.assertEqual(recorded['steering']['degrees'],[-26,-16,-7,0,10,16,26])
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);(root/'README.md').write_text((ROOT/'README.md').read_text())
            saved=root/'firmware/calibration/calibration.json';saved.parent.mkdir(parents=True);saved.write_text(json.dumps(recorded))
            (root/'maxbot.local.json').write_text(json.dumps({'network':{'pairingKey':'private-test-key'},'steering':{'kd':2}}))
            with patch.object(configure,'ROOT',root),patch('sys.argv',['configure.py']),contextlib.redirect_stdout(io.StringIO()):configure.main()
            runtime=json.loads((root/'maxbot.runtime.json').read_text())
            self.assertEqual(runtime['steering']['adc'],recorded['steering']['adc'])
            self.assertEqual(runtime['steering']['kd'],2)
            self.assertEqual(runtime['network']['pairingKey'],'private-test-key')
            self.assertEqual(runtime['hardware']['pwmCalibration']['steeringStartPwm'],[110,110])
            header=(root/'firmware/maxbot/calibration.generated.h').read_text()
            self.assertIn('#define STEER_POINTS 7',header)
            self.assertIn('1158, 975, 849, 785, 688, 608, 461',header)
            self.assertIn('#define MAXBOT_CALIBRATED 0',header)

    def test_tracker_geometry_uses_rear_axle_reference_not_tire_spacing(self):
        c=configure.read_config((ROOT/'README.md').read_text())
        saved=json.loads((ROOT/'firmware/calibration/calibration.json').read_text())
        configure.import_calibration(c,saved)
        measurements=json.loads((ROOT/'firmware/calibration/geometry-measurements.json').read_text())
        front_distance=measurements['measurements']['cameraDistanceBehindFrontM']['value']
        self.assertAlmostEqual(c['camera']['forwardM'],c['car']['wheelbaseM']+c['car']['frontOverhangM']-front_distance)
        self.assertAlmostEqual(c['camera']['forwardM'],.06094)
        self.assertEqual(c['camera']['heightM'],.09924)
        self.assertEqual(c['car']['trackWidthM'],.127)
        self.assertEqual(c['car']['widthM'],.144)
        self.assertEqual(c['camera']['leftM'],.05090)
        self.assertGreater(c['camera']['leftM'],0)
        self.assertLess(c['camera']['leftM'],c['car']['widthM']/2)
        self.assertEqual(measurements['measurements']['widthM']['value'],c['car']['widthM'])
        self.assertEqual(measurements['measurements']['cameraLeftM']['value'],c['camera']['leftM'])
        self.assertEqual(c['car']['rearOverhangM'],.055)
        for field in ['pitchDownDeg','yawLeftDeg']:self.assertIsNone(c['camera'][field])

    def test_drive_polarity_changes_fingerprint_and_must_be_measured(self):
        c=configure.read_config((ROOT/'README.md').read_text());old=configure.actuator_id(c)
        c['hardware']['drivePolarity']=-1
        self.assertNotEqual(old,configure.actuator_id(c))
        c['steering'].update(leftDeg=25,rightDeg=-25,travelTimeS=.3,deadbandDeg=1,adc=[700,1900,3100],degrees=[-25,0,25],kp=4,ki=0,kd=0,maxPwm=80)
        c['power'].update(batteryMonitoring=True,senseSource='battery',dividerRatio=4,adcReferenceMv=3300,adcOffsetMv=0,fullV=8.4,warningV=7.2,stopV=6.8)
        c['hardware'].update(batteryPin=34,physicalStopPin=33,steerPolarity=-1,driveBrakeVerified=True)
        c['network']['pairingKey']='synthetic-test-key-at-least-32-characters'
        c['commissioning'].update(powerVerified=True,steeringMeasured=True)
        header,ready=configure.firmware_header(c)
        self.assertTrue(ready);self.assertIn('#define DRIVE_POLARITY -1',header)
        c['hardware']['drivePolarity']=None
        self.assertFalse(configure.firmware_header(c)[1])
        c['hardware'].update(drivePolarity=1,steerPotPin=14)
        self.assertFalse(configure.firmware_header(c)[1])

    def test_cli_import_and_dry_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp);(root/'README.md').write_text((ROOT/'README.md').read_text())
            private=root/'maxbot.local.json';private.write_text(json.dumps({'network':{'pairingKey':'private-test-value'},'hardware':{'wifiSsid':'private-ssid'}}))
            export=root/'capture.json';export.write_text(json.dumps({'hardware':{'drivePolarity':-1},'steering':{'adc':[800,2000,3200],'degrees':[-25,0,25]},'commissioning':{'steeringMeasured':False}}))
            with patch.object(configure,'ROOT',root),patch('sys.argv',['configure.py','--import-calibration',str(export)]),contextlib.redirect_stdout(io.StringIO()):configure.main()
            stored=json.loads(private.read_text());runtime=json.loads((root/'maxbot.runtime.json').read_text())
            self.assertEqual(stored['network']['pairingKey'],'private-test-value');self.assertEqual(stored['hardware']['wifiSsid'],'private-ssid')
            self.assertEqual(runtime['hardware']['drivePolarity'],-1);self.assertEqual(runtime['firmwareCalibrationId'],configure.actuator_id(runtime))
            self.assertFalse(runtime['commissioning']['steeringMeasured'])
            self.assertIn('#define MAXBOT_CALIBRATED 0',(root/'firmware/maxbot/calibration.generated.h').read_text())
            original=private.read_bytes();export.write_text(json.dumps({'hardware':{'drivePolarity':1}}))
            with patch.object(configure,'ROOT',root),patch('sys.argv',['configure.py','--check','--import-calibration',str(export)]),contextlib.redirect_stdout(io.StringIO()):configure.main()
            self.assertEqual(private.read_bytes(),original)

    def test_optional_stop_and_real_battery_sensing_gates(self):
        c=configure.read_config((ROOT/'README.md').read_text())
        c['steering'].update(leftDeg=26,rightDeg=-26,travelTimeS=.06,deadbandDeg=3.5,adc=[1158,785,461],degrees=[-26,0,26],kp=50,ki=3,kd=1.3,maxPwm=255)
        c['power'].update(batteryMonitoring=True,senseSource='battery',dividerRatio=3,adcReferenceMv=3100,adcOffsetMv=0,fullV=8.4,warningV=7,stopV=6)
        c['hardware'].update(batteryPin=34,physicalStopPin=None,steerPolarity=1,drivePolarity=1,driveBrakeVerified=True)
        c['network']['pairingKey']='synthetic-test-key-at-least-32-characters'
        c['commissioning'].update(powerVerified=True,steeringMeasured=True)
        header,ready=configure.firmware_header(c)
        self.assertTrue(ready);self.assertIn('#define STOP_PIN -1',header)
        for pin,expected in [(33,True),(34,False),(35,False),(0,False),(-1,False),(True,False)]:
            c['hardware']['physicalStopPin']=pin
            self.assertEqual(configure.firmware_header(c)[1],expected)
        c['hardware']['physicalStopPin']=None
        c['hardware']['batteryPin']=25
        self.assertFalse(configure.firmware_header(c)[1])
        c['hardware']['batteryPin']=34
        c['power']['senseSource']='regulated5V'
        self.assertFalse(configure.firmware_header(c)[1])
        c['power']['senseSource']='battery'
        old=configure.actuator_id(c)
        c['power']['adcOffsetMv']=None
        self.assertFalse(configure.firmware_header(c)[1])
        c['power']['adcOffsetMv']=0
        c['power']['senseSource']='regulated5V'
        self.assertNotEqual(old,configure.actuator_id(c))
        c['power']['batteryMonitoring']=False
        c['hardware']['batteryPin']=None
        for key in ['dividerRatio','adcReferenceMv','adcOffsetMv','fullV','warningV','stopV']:
            c['power'][key]=None
        header,ready=configure.firmware_header(c)
        self.assertTrue(ready)
        self.assertIn('#define BATTERY_MONITORING_ENABLED 0',header)
        self.assertIn('#define BATTERY_PIN -1',header)
        # Legacy pin overrides are inactive when monitoring is explicitly disabled.
        c['hardware']['batteryPin']=25
        self.assertIn('#define BATTERY_PIN -1',configure.firmware_header(c)[0])
        c['power']['batteryMonitoring']=True
        self.assertFalse(configure.firmware_header(c)[1])
        for invalid in [None,0,'false']:
            c['power']['batteryMonitoring']=invalid
            self.assertFalse(configure.firmware_header(c)[1])

    def test_manual_battery_policy_does_not_fabricate_voltage(self):
        saved=json.loads((ROOT/'firmware/calibration/calibration.json').read_text())
        self.assertIsNone(saved['hardware']['batteryPin'])
        self.assertIsNone(saved['hardware']['physicalStopPin'])
        self.assertEqual(saved['power']['senseSource'],'disabled')
        self.assertFalse(saved['power']['batteryMonitoring'])
        self.assertEqual(saved['power']['manualCheckTargetV'],12)
        self.assertEqual(saved['power']['dividerRatio'],1.5)
        self.assertEqual([saved['power'][k] for k in ['fullV','warningV','stopV']],[12.6,10.5,9])
        self.assertIsNone(saved['power']['adcReferenceMv'])
        self.assertIsNone(saved['power']['adcOffsetMv'])

    def test_actual_manual_profile_coasts_without_falsifying_commissioning(self):
        c=configure.read_config((ROOT/'README.md').read_text())
        configure.import_calibration(c,json.loads((ROOT/'firmware/calibration/calibration.json').read_text()))
        c['network']['pairingKey']='synthetic-manual-test-key-at-least-32-characters'
        # Exercise an explicitly unverified fixture regardless of later user confirmations.
        c['hardware']['driveBrakeVerified']=False
        c['commissioning'].update(steeringMeasured=False,powerVerified=False,brakingTested=False)
        self.assertFalse(c['hardware']['driveBrakeVerified'])
        self.assertFalse(c['commissioning']['steeringMeasured'])
        self.assertFalse(c['commissioning']['powerVerified'])
        header,autonomous=configure.firmware_header(c)
        self.assertFalse(autonomous)
        for macro in ['MAXBOT_ACTUATORS_READY 1','MANUAL_COMMISSIONING_ENABLED 1','MANUAL_MAX_PWM 255','DRIVE_BRAKE_VERIFIED 0','BATTERY_MONITORING_ENABLED 0']:
            self.assertIn('#define '+macro,header)
        old=configure.actuator_id(c)
        c['manual']['maxPwm']=150
        self.assertNotEqual(old,configure.actuator_id(c))
        c['steering']['adc']=[0,785,461]
        self.assertIn('#define MANUAL_COMMISSIONING_ENABLED 0',configure.firmware_header(c)[0])

    def test_3s_motor_duty_profile_and_invalid_supply(self):
        c=configure.read_config((ROOT/'README.md').read_text())
        self.assertEqual(configure.pwm_ceiling(c),170)
        header=configure.pwm_header(c)
        self.assertIn('#define DRIVE_MAX_DUTY 170',header)
        self.assertIn('#define STEER_MAX_DUTY 170',header)
        original=configure.actuator_id(c)
        c['power']['packMaxV']=8.4
        self.assertEqual(configure.pwm_ceiling(c),255)
        self.assertNotEqual(original,configure.actuator_id(c))
        for bad in [0,-1,None,True,float('nan'),16.8]:
            c['power']['packMaxV']=bad
            self.assertEqual(configure.pwm_ceiling(c),0)
            self.assertIn('#define MAXBOT_ACTUATORS_READY 0',configure.firmware_header(c)[0])

    def test_duplicate_readme_blocks_rejected(self):
        with self.assertRaises(ValueError):configure.read_config((ROOT/'README.md').read_text()*2)

    def test_merge_does_not_delete_measurements(self):
        c={'network':{'espPort':4242,'pairingKey':''}};configure.merge(c,{'network':{'pairingKey':'x'*32}})
        self.assertEqual(c['network']['espPort'],4242)

    def test_usb_bandwidth_and_firmware_fingerprint(self):
        c=configure.read_config((ROOT/'README.md').read_text())
        packet={'v':1,'type':'command','boot':'f'*16,'session':'a'*32,'seq':999999999,'issued_ms':999999999999,'ttl_ms':100,'mode':'DRIVE','steering_mdeg':35000,'pwm':255,'mac':'f'*64}
        bytes_per_second=(len(json.dumps(packet,separators=(',',':')))+1)*50
        self.assertLess(bytes_per_second*10,c['network']['baud']*.75)
        original=configure.actuator_id(c);c['network']['baud']=230400
        self.assertNotEqual(original,configure.actuator_id(c))

    def test_missing_or_bad_labels_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=pathlib.Path(tmp)/'x.txt'
            with self.assertRaises(ValueError):dataset.validate_label(p)
            for text in ['1 .5 .5 .2 .2','0 nan .5 .2 .2','0 .01 .5 .5 .2','0 .5 .5 0 .2']:
                p.write_text(text)
                with self.assertRaises(ValueError):dataset.validate_label(p)
            p.write_text('');dataset.validate_label(p)

    def test_split_is_by_session_and_detects_corruption(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=pathlib.Path(tmp)/'source';source.mkdir()
            for s in range(6):
                session=source/f'run{s}';(session/'images').mkdir(parents=True);(session/'labels').mkdir()
                for i in range(2):
                    (session/'images'/f'{i}.png').write_bytes(f'image{s}-{i}'.encode());(session/'labels'/f'{i}.txt').write_text('0 .5 .5 .2 .2\n')
            output=pathlib.Path(tmp)/'dataset';manifest=dataset.split(source,output)
            self.assertEqual(dataset.validate_dataset(output),12)
            self.assertEqual(set(manifest['sessions'].values()),{'train','val','test'})
            for session in manifest['sessions']:
                self.assertEqual(len({e['split'] for e in manifest['images'] if e['session']==session}),1)
            first=manifest['images'][0];(output/'images'/first['split']/first['name']).write_bytes(b'changed')
            with self.assertRaises(ValueError):dataset.validate_dataset(output)

    def test_collision_output_is_only_a_candidate(self):
        events=[json.dumps({'type':'telemetry','cones':[[1,0],[2,0],[3,0],[4,0]]})]
        events += [json.dumps({'type':'detections','capture_ns':i,'points':[[1,.2]]}) for i in range(3)]
        result=dataset.collision_candidates(events);self.assertEqual(result[0]['label'],'collision_candidate')

if __name__=='__main__':unittest.main()
