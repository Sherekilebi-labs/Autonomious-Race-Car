---
name: MaxBot console
description: Local supervision for a Pixel-powered cone-slalom car
colors:
  primary: "#0064b2"
  surface: "#ffffff"
  background: "#f5f7f9"
  text: "#182530"
  secondary-text: "#4c606e"
  divider: "#cbd4dc"
  emergency: "#ae1829"
typography:
  display:
    fontFamily: "Gilroy, Arial, sans-serif"
    fontSize: "30px"
    fontWeight: 700
    letterSpacing: "-0.025em"
  title:
    fontFamily: "Gilroy, Arial, sans-serif"
    fontSize: "18px"
    fontWeight: 700
rounded:
  control: "6px"
  viewport: "8px"
spacing:
  control-gap: "8px"
  desktop-gutter: "32px"
  columns: "28px"
components:
  emergency-button:
    backgroundColor: "{colors.emergency}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    padding: "10px 14px"
---

# MaxBot design system

## Overview

An operator console for an indoor robot. Preserve OpenBot's blue control accent and existing locally hosted Gilroy fonts. The car's actual state, connection age and stop authority determine the information hierarchy.

## Colors

White sections sit on a light neutral page. Blue identifies planned paths and starting controls. Red is reserved for emergency stop and faults. Orange identifies detected cones. Disabled controls are visibly unavailable.

## Typography

Gilroy is loaded from existing repository assets; no remote font request. Measurements use tabular numerals. Device and model failures use plain-language text rather than color alone.

## Layout

Video plus run controls occupy the main column, telemetry/manual controls the right column. Course map and readiness follow below. The header and emergency stop remain sticky. At 650px, sections stack without hiding manual control or readiness.

## Elevation & Depth

Flat surfaces separated by single rules; no decorative shadows or simulated physical materials.

## Shapes

Six-pixel control corners and eight-pixel viewport corners. The console contains no nested metric cards.

## Components

Manual drive becomes keyboard-ready after manual arming. WASD and press-and-hold on-screen direction buttons ramp signed drive/steering requests; release decays toward zero at the configured rate. Drive and steering rate fields use percent of full scale per second (100 and 250 by default). Focus loss, stale telemetry and disconnect clear keyboard input immediately. Emergency stop has an Escape shortcut and requests acknowledgement. Inputs have visible labels, focus outlines and minimum 44px buttons. Empty camera/map and stale telemetry states are explicit.

## Do's and Don'ts

Keep measurement units and estimated-versus-actual distinctions visible. Never invent live telemetry, certify a stop without acknowledgement, or use a video frame's disappearance as proof of cone passage.
