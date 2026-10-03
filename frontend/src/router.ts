import {createRouter,createWebHistory} from 'vue-router';
import {useAuthStore} from '@/stores/auth';
import {pageTitle} from '@/utils/pageTitle';

const routes=[
  {path:'/login',component:()=>import('@/views/LoginView.vue'),meta:{title:'登录'}},
  {path:'/',component:()=>import('@/layout/AppLayout.vue'),children:[
    {path:'',redirect:'/dashboard'},
    {path:'dashboard',component:()=>import('@/views/DashboardView.vue'),meta:{title:'培养运营工作台'}},
    {path:'employees',redirect:'/employee-directory'},
    {path:'employee-directory',component:()=>import('@/views/EmployeeDirectoryView.vue'),meta:{permission:'employee:read',title:'人员台账'}},
    {path:'location-reports',component:()=>import('@/views/LocationReportsView.vue'),meta:{permission:'employee:read',title:'人员流动'}},
    {path:'courses',redirect:()=>useAuthStore().user?.role==='EMPLOYEE'?'/courses/my':'/courses/manage'},
    {path:'courses/manage',component:()=>import('@/views/CourseCatalogView.vue'),meta:{permission:'course:manage',title:'课程库'}},
    {path:'courses/sessions',component:()=>import('@/views/CourseSessionsView.vue'),meta:{permission:'course:manage',title:'场次安排'}},
    {path:'courses/attendance',component:()=>import('@/views/CourseAttendanceView.vue'),meta:{title:'签到管理'}},
    {path:'courses/my',component:()=>import('@/views/MyCoursesView.vue'),meta:{title:'我的课程'}},
    {path:'courses/learning',component:()=>import('@/views/CourseLearningView.vue'),meta:{title:'课件学习'}},
    {path:'courses/materials',component:()=>import('@/views/CoursewareManagementView.vue'),meta:{permission:'course:manage',title:'课件管理'}},
    {path:'training-plans',redirect:'/training-plans/manage'},
    {path:'training-plans/manage',component:()=>import('@/views/TrainingPlanManagementView.vue'),meta:{permission:'task:manage',title:'任务管理'}},
    {path:'training-plans/tasks',component:()=>import('@/views/TrainingPlanTasksView.vue'),meta:{permission:'task:manage',title:'任务编排'}},
    {path:'training-plans/tracking',component:()=>import('@/views/TasksView.vue'),meta:{title:'任务跟踪'}},
    {path:'task-scoring',component:()=>import('@/views/TaskScoringView.vue'),meta:{permission:'task:score',title:'任务评分'}},
    {path:'tasks',component:()=>import('@/views/TasksView.vue'),meta:{title:'任务下发'}},
    {path:'evaluation',redirect:()=>useAuthStore().user?.role==='EMPLOYEE'?'/evaluation/results':'/evaluation/workbench'},
    {path:'evaluation/workbench',component:()=>import('@/views/evaluation/EvaluationWorkbenchView.vue'),meta:{permission:'evaluation:view',title:'评价工作台'}},
    {path:'evaluation/assignments',component:()=>import('@/views/evaluation/EvaluationAssignmentsView.vue'),meta:{permission:'evaluation:manage',title:'评分任务编排'}},
    {path:'evaluation/assignments/:id',component:()=>import('@/views/evaluation/EvaluationAssignmentDetailView.vue'),meta:{permission:'evaluation:manage',title:'评分任务详情'}},
    {path:'evaluation/my-tasks',component:()=>import('@/views/evaluation/MyEvaluationTasksView.vue'),meta:{permission:'evaluation:submit',title:'我的评分任务'}},
    {path:'evaluation/monthly',component:()=>import('@/views/evaluation/EvaluationMonthlyView.vue'),meta:{permission:'evaluation:view',title:'月度评价工作台'}},
    {path:'evaluation/templates',component:()=>import('@/views/evaluation/EvaluationTemplatesView.vue'),meta:{permission:'evaluation:manage',title:'评价模板与月度方案'}},
    {path:'evaluation/results',component:()=>import('@/views/evaluation/EvaluationResultsView.vue'),meta:{permission:'evaluation:view',title:'评价结果中心'}},
    {path:'evaluation/results/history-imports',component:()=>import('@/views/HistoryImportEvaluationView.vue'),meta:{permission:'history:import',title:'历史评价导入'}},
    {path:'history-imports',redirect:'/exams/results/history-imports'},
    {path:'exams/results/history-imports',component:()=>import('@/views/HistoryImportExamView.vue'),meta:{permission:'history:import',title:'历史成绩导入'}},
    {path:'exams',redirect:'/exams/my'},
    {path:'exams/my',component:()=>import('@/views/exams/MyExamsView.vue'),meta:{title:'我的考试'}},
    {path:'exams/questions',component:()=>import('@/views/exams/ExamQuestionBankView.vue'),meta:{permission:'exam:manage',title:'题库管理'}},
    {path:'exams/papers',component:()=>import('@/views/exams/ExamPapersView.vue'),meta:{permission:'exam:manage',title:'试卷管理'}},
    {path:'exams/plans',component:()=>import('@/views/exams/ExamPlansView.vue'),meta:{permission:'exam:manage',title:'考试计划'}},
    {path:'exams/results',component:()=>import('@/views/exams/ExamResultsView.vue'),meta:{title:'成绩管理'}},
    {path:'station-change-review',component:()=>import('@/views/StationChangeReviewView.vue'),meta:{permission:'master:manage',title:'调站审批'}},
    {path:'dictionaries',component:()=>import('@/views/DictionaryManagementView.vue'),meta:{permission:'master:manage',title:'字典值管理'}},
    {path:'users',component:()=>import('@/views/UsersView.vue'),meta:{permission:'user:employee:manage',title:'账号管理'}},
    {path:'profile',component:()=>import('@/views/ProfileView.vue'),meta:{title:'账号设置'}}
  ]}
];

const router=createRouter({history:createWebHistory(),routes});
router.beforeEach(to=>{
  const a=useAuthStore();
  if(to.path!='/login'&&!a.user)return '/login';
  if(to.path==='/login'&&a.user)return '/dashboard';
  if(a.user?.mustChangePassword&&to.path!='/profile')return '/profile';
  if(a.user?.role==='EMPLOYEE'&&to.path==='/employee-directory')return '/profile';
  if(a.user?.role==='EMPLOYEE'&&to.path.startsWith('/evaluation/')&&to.path!=='/evaluation/results')return '/evaluation/results';
  if(a.user?.role==='EMPLOYEE'&&['/courses/manage','/courses/sessions','/courses/materials'].includes(to.path))return '/courses/learning';
  if(a.user?.role!=='EMPLOYEE'&&['/courses/my','/courses/learning'].includes(to.path))return a.can('course:manage')?'/courses/manage':'/courses/attendance';
  if(a.user?.role==='EMPLOYEE'&&to.path==='/exams/results')return {path:'/exams/my',query:{section:'results'}};
  if(a.user?.role!=='EMPLOYEE'&&to.path==='/exams/my')return a.can('exam:manage')?'/exams/plans':'/exams/results';
  const permission=to.meta.permission as string|undefined;
  if(permission&&!a.can(permission))return '/dashboard';
});
router.afterEach(to=>{
  const role=useAuthStore().user?.role;
  document.title=pageTitle(to.path,role,typeof to.meta.title==='string'?to.meta.title:undefined);
});
export default router;
