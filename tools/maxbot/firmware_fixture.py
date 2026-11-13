#!/usr/bin/env python3
"""Generate a SYNTHETIC compile-only fixture. Never flash this calibration onto a car."""
import pathlib
import argparse
from configure import ROOT,read_config,firmware_header,pwm_header

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--without-stop',action='store_true')
    parser.add_argument('--without-battery',action='store_true')
    args=parser.parse_args()
    c=read_config((ROOT/'README.md').read_text())
    c['steering'].update(leftDeg=35,rightDeg=-35,travelTimeS=.25,deadbandDeg=1,adc=[800,2000,3200],degrees=[-35,0,35],kp=3,ki=0,kd=.1,maxPwm=100)
    c['power'].update(batteryMonitoring=True,senseSource='battery',dividerRatio=4,adcReferenceMv=3300,adcOffsetMv=0,fullV=8.4,warningV=7,stopV=6.8)
    c['hardware'].update(driveForwardPin=4,driveReversePin=16,steerForwardPin=22,steerReversePin=23,steerPotPin=35,batteryPin=34,physicalStopPin=33,steerPolarity=1,drivePolarity=1,driveBrakeVerified=True)
    c['network']['pairingKey']='compile-only-synthetic-key-do-not-use'
    c['commissioning'].update(powerVerified=True,steeringMeasured=True)
    if args.without_stop:c['hardware']['physicalStopPin']=None
    if args.without_battery:
        c['power'].update(batteryMonitoring=False,senseSource='disabled')
        for key in ['dividerRatio','adcReferenceMv','adcOffsetMv','fullV','warningV','stopV']:c['power'][key]=None
        c['hardware']['batteryPin']=None
    header,ready=firmware_header(c)
    if not ready:raise RuntimeError('Synthetic compile fixture was unexpectedly rejected')
    suffix=('-no-battery' if args.without_battery else '')+('-no-stop' if args.without_stop else '')
    dest=ROOT/('.tools/firmware-fixture'+suffix+'/maxbot');dest.mkdir(parents=True,exist_ok=True)
    (dest/'maxbot.ino').write_text((ROOT/'firmware/maxbot/maxbot.ino').read_text())
    (dest/'calibration.generated.h').write_text('// SYNTHETIC COMPILE TEST ONLY - DO NOT FLASH\n'+header)
    (dest/'actuator_pwm.generated.h').write_text(pwm_header(c))
    print(dest)

if __name__=='__main__':main()
