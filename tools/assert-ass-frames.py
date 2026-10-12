#!/usr/bin/env python3
"""Host-side visual assertion; Pillow is needed only for this test, never by the app."""
from pathlib import Path
import sys
from PIL import Image, ImageChops, ImageStat
root=Path(sys.argv[1])
reference=Image.open(root/'styled-reference.png').convert('RGB')
assert reference.size==(1920,1080)
region=(120,850,1800,1020)
def blue(image):
    pixels=image.crop(region).getdata()
    return sum(1 for r,g,b in pixels if b>60 and b>r*1.5+10 and b>g*1.5+10)
def green(image):
    return sum(1 for r,g,b in image.crop((100,130,850,400)).getdata() if g>100 and g>r*1.5 and g>b*1.5)
expected=blue(reference)
assert expected>1000,expected
for name in ['ass-authored','ass-selected','ass-after-seek']:
    actual=Image.open(root/(name+'.png')).convert('RGB')
    assert actual.size==reference.size,actual.size
    actual_blue=blue(actual)
    assert .8*expected<actual_blue<1.2*expected,(name,'authored outline coverage',expected,actual_blue)
    error=sum(ImageStat.Stat(ImageChops.difference(reference.crop(region),actual.crop(region))).mean)/3
    assert error<12,(name,'font/placement differs from reference',error)
    assert green(actual)>400,(name,'positioned sign missing')
    print(name,'outline pixels',actual_blue,'mean pixel error',round(error,3))
assert blue(Image.open(root/'ass-off.png').convert('RGB'))<30,'Off must clear the ASS overlay'
print('PASS: attached-font shape and placement match reference; blue outlines and positioned sign survive switching/seeking; Off clears output')
