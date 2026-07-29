import {createRouter,createWebHistory} from 'vue-router';
import {useAuthStore} from '@/stores/auth';

const routes=[
  {path:'/login',component:()=>import('@/views/LoginView.vue')},
  {path:'/',component:()=>import('@/layout/AppLayout.vue'),children:[
    {path:'',redirect:'/dashboard'},
    {path:'dashboard',component:()=>import('@/views/DashboardView.vue')},
    {path:'employees',redirect:'/employee-directory'},
    {path:'employee-directory',component:()=>import('@/views/EmployeeDirectoryView.vue'),meta:{permission:'employee:read'}},
    {path:'location-reports',component:()=>import('@/views/LocationReportsView.vue'),meta:{permission:'employee:read'}},
    {path:'courses',redirect:()=>useAuthStore().user?.role==='EMPLOYEE'?'/courses/my':'/courses/manage'},
    {path:'courses/manage',component:()=>import('@/views/CourseCatalogView.vue'),meta:{permission:'course:manage'}},
    {path:'courses/sessions',component:()=>import('@/views/CourseSessionsView.vue'),meta:{permission:'course:manage'}},
    {path:'courses/attendance',component:()=>import('@/views/CourseAttendanceView.vue')},
    {path:'courses/my',component:()=>import('@/views/MyCoursesView.vue')},
    {path:'training-plans',redirect:'/training-plans/manage'},
    {path:'training-plans/manage',component:()=>import('@/views/TrainingPlanManagementView.vue'),meta:{permission:'task:manage'}},
    {path:'training-plans/tasks',component:()=>import('@/views/TrainingPlanTasksView.vue'),meta:{permission:'task:manage'}},
    {path:'training-plans/tracking',component:()=>import('@/views/TasksView.vue')},
    {path:'tasks',component:()=>import('@/views/TasksView.vue')},
    {path:'evaluation',component:()=>import('@/views/EvaluationView.vue'),meta:{permission:'evaluation:view'}},
    {path:'exams',redirect:'/exams/my'},
    {path:'exams/my',component:()=>import('@/views/exams/MyExamsView.vue')},
    {path:'exams/questions',component:()=>import('@/views/exams/ExamQuestionBankView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/papers',component:()=>import('@/views/exams/ExamPapersView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/plans',component:()=>import('@/views/exams/ExamPlansView.vue'),meta:{permission:'exam:manage'}},
    {path:'exams/results',component:()=>import('@/views/exams/ExamResultsView.vue')},
    {path:'station-change-review',component:()=>import('@/views/StationChangeReviewView.vue'),meta:{permission:'master:manage'}},
    {path:'users',component:()=>import('@/views/UsersView.vue'),meta:{permission:'user:employee:manage'}},
    {path:'profile',component:()=>import('@/views/ProfileView.vue')}
  ]}
];

const router=createRouter({history:createWebHistory(),routes});
router.beforeEach(to=>{
  const a=useAuthStore();
  if(to.path!='/login'&&!a.user)return '/login';
  if(to.path==='/login'&&a.user)return '/dashboard';
  if(a.user?.mustChangePassword&&to.path!='/profile')return '/profile';
  if(a.user?.role==='EMPLOYEE'&&to.path==='/employee-directory')return '/profile';
  if(a.user?.role==='EMPLOYEE'&&['/courses/manage','/courses/sessions'].includes(to.path))return '/courses/my';
  if(a.user?.role!=='EMPLOYEE'&&to.path==='/courses/my')return a.can('course:manage')?'/courses/manage':'/courses/attendance';
  if(a.user?.role!=='EMPLOYEE'&&to.path==='/exams/my')return a.can('exam:manage')?'/exams/plans':'/exams/results';
  const permission=to.meta.permission as string|undefined;
  if(permission&&!a.can(permission))return '/dashboard';
});
export default router;
