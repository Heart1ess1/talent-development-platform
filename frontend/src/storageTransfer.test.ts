import {beforeEach,describe,expect,it,vi} from 'vitest'

const {axiosRequest,axiosIsError,apiGet,apiPost,apiDelete}=vi.hoisted(()=>({
  axiosRequest:vi.fn(),
  axiosIsError:vi.fn(),
  apiGet:vi.fn(),
  apiPost:vi.fn(),
  apiDelete:vi.fn()
}))

vi.mock('axios',()=>({
  default:{request:axiosRequest,isAxiosError:axiosIsError}
}))

vi.mock('@/api',()=>({
  api:{get:apiGet,post:apiPost,delete:apiDelete}
}))

import {createUploadTicket,uploadWithStorageFallback} from './storageTransfer'

function ticket(method='PUT'){
  return {
    data:{
      ticketId:'ticket-1',
      uploadUrl:'https://example.oss/upload',
      method,
      headers:{'Content-Type':'application/pdf'},
      formFields:{key:'private/result.pdf'},
      expiresAt:'2026-08-28T12:00:00Z'
    }
  }
}

describe('OSS storage transfer',()=>{
  beforeEach(()=>{
    vi.clearAllMocks()
    apiDelete.mockResolvedValue({data:null})
    axiosIsError.mockReturnValue(false)
  })

  it('reports precise PUT upload progress',async()=>{
    apiPost.mockResolvedValue(ticket())
    axiosRequest.mockImplementation(async options=>{
      options.onUploadProgress({loaded:50,total:100})
      options.onUploadProgress({loaded:100,total:100})
      return {status:200}
    })
    const progress=vi.fn()

    await createUploadTicket('/ticket',new File(['result'],'result.pdf',{type:'application/pdf'}),progress)

    expect(progress).toHaveBeenNthCalledWith(1,{loaded:50,total:100,percent:50})
    expect(progress).toHaveBeenNthCalledWith(2,{loaded:100,total:100,percent:100})
    expect(axiosRequest.mock.calls[0]![0].headers).toEqual({'Content-Type':'application/pdf'})
  })

  it('supports POST policy uploads without forwarding application headers',async()=>{
    apiPost.mockResolvedValue(ticket('POST'))
    axiosRequest.mockResolvedValue({status:204})

    await createUploadTicket('/ticket',new File(['result'],'result.pdf',{type:'application/pdf'}))

    const options=axiosRequest.mock.calls[0]![0]
    expect(options.headers).toEqual({})
    expect(options.data).toBeInstanceOf(FormData)
    expect(options.data.get('key')).toBe('private/result.pdf')
    expect(options.data.get('file')).toBeInstanceOf(File)
  })

  it('uses indeterminate progress when the browser omits total bytes',async()=>{
    apiPost.mockResolvedValue(ticket())
    axiosRequest.mockImplementation(async options=>{
      options.onUploadProgress({loaded:5})
      return {status:200}
    })
    const progress=vi.fn()

    await createUploadTicket('/ticket',new File(['result'],'result.pdf'),progress)

    expect(progress).toHaveBeenCalledWith({loaded:5,total:undefined,percent:undefined})
  })

  it('abandons the ticket and preserves the OSS status when upload fails',async()=>{
    apiPost.mockResolvedValue(ticket())
    const failure={response:{status:403}}
    axiosRequest.mockRejectedValue(failure)
    axiosIsError.mockReturnValue(true)

    await expect(createUploadTicket('/ticket',new File(['result'],'result.pdf')))
      .rejects.toThrow('OSS 上传失败（HTTP 403）')
    expect(apiDelete).toHaveBeenCalledWith(
      '/storage/upload-tickets/ticket-1',
      {silentError:true}
    )
  })

  it('abandons an uploaded ticket when final registration fails',async()=>{
    apiGet.mockResolvedValue({data:{directUpload:true,signedDownload:true}})
    apiPost.mockResolvedValueOnce(ticket()).mockRejectedValueOnce(new Error('登记失败'))
    axiosRequest.mockResolvedValue({status:200})

    await expect(uploadWithStorageFallback({
      file:new File(['result'],'result.pdf'),
      legacyUrl:'/legacy',
      ticketUrl:'/ticket',
      completeUrl:id=>`/complete/${id}`
    })).rejects.toThrow('登记失败')

    expect(apiPost).toHaveBeenLastCalledWith('/complete/ticket-1')
    expect(apiDelete).toHaveBeenCalledWith(
      '/storage/upload-tickets/ticket-1',
      {silentError:true}
    )
  })
})
