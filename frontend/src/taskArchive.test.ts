import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest'

const {get,post}=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}))
vi.mock('@/api',()=>({api:{get,post}}))
import {archiveErrorMessage,downloadTaskArchive} from './taskArchive'

const job=(state:string,extra={})=>({jobId:'job-1',taskId:18,state,totalFiles:73,processedFiles:28,
  missingFiles:0,currentFile:'月报.docx',filename:'月报.zip',size:100,message:'打包失败',...extra})

describe('background task archive',()=>{
  beforeEach(()=>{vi.clearAllMocks();vi.useFakeTimers()})
  afterEach(()=>vi.useRealTimers())

  it('polls actual attachment progress then downloads with a separate timeout and progress',async()=>{
    post.mockResolvedValue({data:job('QUEUED',{processedFiles:0})})
    get.mockResolvedValueOnce({data:job('RUNNING')}).mockResolvedValueOnce({data:job('READY',{processedFiles:73})})
      .mockImplementationOnce(async(_url,options)=>{
        options.onDownloadProgress({loaded:65,total:100})
        return new Blob(['zip'])
      })
    const update=vi.fn()
    const result=downloadTaskArchive(18,new AbortController().signal,update)
    await vi.advanceTimersByTimeAsync(4000)
    expect((await result).filename).toBe('月报.zip')
    expect(update.mock.calls.map(([value])=>value.stage)).toEqual(['PREPARING','QUEUED','RUNNING','DOWNLOADING','DOWNLOADING','DONE'])
    expect(update.mock.calls[2]![0].percent).toBe(38)
    expect(update.mock.calls[4]![0].percent).toBe(65)
    expect(get.mock.calls[2]![1].timeout).toBe(600000)
  })

  it('reuses completed ZIP on download retry',async()=>{
    get.mockResolvedValueOnce({data:job('READY')}).mockResolvedValueOnce(new Blob(['zip']))
    await downloadTaskArchive(18,new AbortController().signal,vi.fn(),'job-1')
    expect(post).not.toHaveBeenCalled()
    expect(get.mock.calls[1]![0]).toBe('/submission-archives/job-1/download')
  })

  it('creates a fresh job after restart or expiry',async()=>{
    get.mockRejectedValueOnce({response:{status:410}}).mockResolvedValueOnce(new Blob(['zip']))
    post.mockResolvedValue({data:job('READY')})
    await downloadTaskArchive(18,new AbortController().signal,vi.fn(),'job-1')
    expect(post).toHaveBeenCalledTimes(1)
  })

  it('stops polling on close without scheduling further requests',async()=>{
    post.mockResolvedValue({data:job('QUEUED')})
    const controller=new AbortController()
    const result=downloadTaskArchive(18,controller.signal,vi.fn())
    const rejected=expect(result).rejects.toHaveProperty('name','AbortError')
    await vi.advanceTimersByTimeAsync(0)
    controller.abort()
    await rejected
    await vi.advanceTimersByTimeAsync(10000)
    expect(get).not.toHaveBeenCalled()
  })

  it('shows failed build instead of attempting to download',async()=>{
    post.mockResolvedValue({data:job('FAILED')})
    await expect(downloadTaskArchive(18,new AbortController().signal,vi.fn())).rejects.toThrow('打包失败')
    expect(get).not.toHaveBeenCalled()
  })

  it('decodes blob API errors and distinguishes download timeout',async()=>{
    expect(await archiveErrorMessage({response:{data:new Blob(['{"message":"无权访问该打包任务"}'])}})).toBe('无权访问该打包任务')
    expect(await archiveErrorMessage({code:'ECONNABORTED'})).toBe('下载超时，请重试下载')
  })
})
