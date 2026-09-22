export interface PageResult<T>{records:T[];total:number;page:number;size:number}
export interface EmployeeSummary{id:number;employeeNo:string;name:string;avatarToken?:string;batchName?:string;className?:string;businessUnitName?:string;stationName?:string;technicalMentorName?:string;skillMentorName?:string;onboardDate?:string}
export interface Metric{key:string;label:string;value:number|null;numerator:number|null;denominator:number|null;unit:string;state:string;note:string}
export interface ChartItem{key:string;label:string;value:number;maxValue?:number;meta?:string}
export interface Overview{employee:EmployeeSummary;latestLocation?:{location:string;occurredAt:string;reportedAt:string};metrics:Metric[];taskStatus:ChartItem[];courseParticipation:ChartItem[];examTrend:ChartItem[];monthlyEvaluationTrend:ChartItem[];quarterlyEvaluationTrend:ChartItem[];fetchedAt:string}
