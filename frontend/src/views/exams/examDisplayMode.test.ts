import {describe,expect,it} from 'vitest'
import {buildExamClientProfile,proctorModeForFullscreenResult} from './examDisplayMode'

describe('exam display mode',()=>{
  it('uses mobile compatible mode for iPhone Safari without the fullscreen API',()=>{
    const profile=buildExamClientProfile({
      userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1',
      maxTouchPoints:5,
      standalone:false,
      fullscreenCapable:false
    })
    expect(profile).toMatchObject({ios:true,wechat:false,preferredMode:'MOBILE_COMPATIBLE'})
  })

  it('recognizes the iPhone WeChat web view as mobile compatible',()=>{
    const profile=buildExamClientProfile({
      userAgent:'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 MicroMessenger/8.0.50',
      maxTouchPoints:5,
      standalone:false,
      fullscreenCapable:false
    })
    expect(profile).toMatchObject({ios:true,wechat:true,preferredMode:'MOBILE_COMPATIBLE'})
    expect(profile.clientContext).toContain('wechat')
  })

  it('keeps strict mode on fullscreen-capable browsers and downgrades after a rejected request',()=>{
    const profile=buildExamClientProfile({userAgent:'Desktop Browser',maxTouchPoints:0,standalone:false,fullscreenCapable:true})
    expect(profile.preferredMode).toBe('FULLSCREEN_STRICT')
    expect(proctorModeForFullscreenResult(true)).toBe('FULLSCREEN_STRICT')
    expect(proctorModeForFullscreenResult(false)).toBe('MOBILE_COMPATIBLE')
  })
})
